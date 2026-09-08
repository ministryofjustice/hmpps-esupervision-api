package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Service
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.IArnsApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.ITierApiClient
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

@Service
class OffenderService(
  private val ndiliusApiClient: INdiliusApiClient,
  private val tierApiClient: ITierApiClient,
  private val arnsApiClient: IArnsApiClient,
  @Value("\${api.base.url.tier-ui}") val tierUiBaseUri: String,
) {

  /**
   * Aggregates the case header from NDelius, the Tier API and ARNS.
   *
   * NDelius is authoritative: an unknown CRN is a 404. Any other lookup failure degrades the
   * response instead of failing it - the affected field is null and [OffenderHeaderDetails.errors]
   * records which field is missing and why.
   */
  fun getHeaderDetails(crn: String): OffenderHeaderDetails {
    val contact = fetchField("dateOfBirth", "NDelius", crn) { ndiliusApiClient.getContactDetailsStrict(crn) }
    if (contact.error == HeaderErrorCode.NOT_FOUND) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, "Could not find contact details in NDelius for $crn.")
    }

    // Tier runs on a virtual thread alongside the ARNS lookup so a slow upstream costs the request
    // max(tier, arns) rather than the sum. The per-call executor holds no pooled resources and
    // close() joins the task, which get() has already done.
    val requestAttributes = RequestContextHolder.getRequestAttributes()
    val (tier, risk) = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
      val tierLookup = executor.submit(
        Callable {
          onBehalfOfRequest(requestAttributes) { fetchField("tierScore", "Tier API", crn) { tierApiClient.getTierDetails(crn)?.tierScore } }
        },
      )
      val risk = fetchField("overallRisk", "ARNS API", crn) { arnsApiClient.getRiskWidget(crn)?.overallRisk }
      await("tierScore", tierLookup) to risk
    }

    return OffenderHeaderDetails(
      crn = crn,
      dateOfBirth = contact.value?.dateOfBirth,
      tierScore = tier.value,
      tierDetailsLink = "$tierUiBaseUri/case/$crn",
      overallRisk = risk.value,
      errors = listOfNotNull(contact.toErrorDetails(), tier.toErrorDetails(), risk.toErrorDetails()),
    )
  }

  /**
   * Runs [block] on this thread with the spawning request's [RequestAttributes] rebound.
   *
   * `RequestContextHolder` is a plain ThreadLocal, so a task handed to another thread starts with
   * an empty one. That matters more than it looks: hmpps-kotlin authorises upstream calls with
   * `ServletOAuth2AuthorizedClientExchangeFilterFunction`, whose `authorizeClient` returns
   * `Mono.empty()` when it cannot find an `HttpServletRequest`/`HttpServletResponse` among the
   * request attributes - and its `filter()` then falls through to
   * `exchangeAndHandleResponse(request, next)`, sending the request with *no* Authorization header
   * instead of failing. The upstream answers 401 and nothing says why. Client credentials do not
   * actually need the servlet request; the filter only null-checks it.
   *
   * Safe to share the attributes across threads here because the caller joins the task before
   * returning, so the request outlives it.
   *
   * Restores whatever was bound before rather than clearing. Today that is always nothing - each
   * task gets its own fresh virtual thread - but a pooled executor would hand us a thread that
   * already carried a request, and clearing it would strand the next task on that thread with no
   * context. `setRequestAttributes(null)` resets, so the no-previous case is unchanged.
   */
  private fun <T> onBehalfOfRequest(requestAttributes: RequestAttributes?, block: () -> T): T {
    if (requestAttributes == null) return block()
    val previous = RequestContextHolder.getRequestAttributes()
    RequestContextHolder.setRequestAttributes(requestAttributes)
    return try {
      block()
    } finally {
      RequestContextHolder.setRequestAttributes(previous)
    }
  }

  private data class FieldResult<T>(val field: String, val value: T?, val error: HeaderErrorCode?) {
    fun toErrorDetails(): ErrorDetails? = error?.let { ErrorDetails(field, it) }
  }

  /**
   * Runs [call] and classifies the outcome for [field]. A null result counts as NOT_FOUND so a
   * missing value is never silently indistinguishable from success.
   */
  private fun <T> fetchField(field: String, source: String, crn: String, call: () -> T?): FieldResult<T> = try {
    val value = call()
    if (value == null) {
      LOGGER.info("{} returned no {} for CRN: {}", source, field, crn)
      FieldResult(field, null, HeaderErrorCode.NOT_FOUND)
    } else {
      FieldResult(field, value, null)
    }
  } catch (e: Exception) {
    val code = classify(e)
    // The clients already log failures with a sanitised message. Keep a stack trace here too, but
    // avoid leaking raw upstream messages by logging a sanitised throwable.
    if (code != HeaderErrorCode.NOT_FOUND) {
      val sanitized = RuntimeException(PiiSanitizer.sanitizeException(e, crn)).apply { stackTrace = e.stackTrace }
      LOGGER.warn(
        "Failed to fetch {} from {} ({}): {}: {}",
        field,
        source,
        code,
        e.javaClass.simpleName,
        sanitized.message,
        sanitized,
      )
    }
    FieldResult(field, null, code)
  }

  /**
   * Joins a [fetchField] task. The task classifies its own exceptions, so only an [Error] escaping
   * the callable or an interrupt of this thread can surface here; both degrade the field rather
   * than fail the whole header.
   */
  private fun <T> await(field: String, task: Future<FieldResult<T>>): FieldResult<T> = try {
    task.get()
  } catch (e: ExecutionException) {
    // Only a non-Exception throwable reaches here (fetchField handles the rest), so there is no
    // upstream response body in the message and the full trace can be kept.
    LOGGER.error("Lookup for {} failed unexpectedly", field, e.cause ?: e)
    FieldResult(field, null, HeaderErrorCode.SERVICE_UNAVAILABLE)
  } catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    task.cancel(true)
    FieldResult(field, null, HeaderErrorCode.SERVICE_UNAVAILABLE)
  }

  private fun classify(e: Exception): HeaderErrorCode = when (val status = e.upstreamStatus()) {
    null -> HeaderErrorCode.SERVICE_UNAVAILABLE
    HttpStatus.NOT_FOUND -> HeaderErrorCode.NOT_FOUND
    else -> if (status.is4xxClientError) HeaderErrorCode.REQUEST_REJECTED else HeaderErrorCode.SERVICE_UNAVAILABLE
  }

  /**
   * The HTTP status an upstream answered with. Tier and ARNS translate every error, and NDelius
   * translates 4xx, but NDelius lets 5xx through raw so the ndiliusApi breaker can record it.
   */
  private fun Exception.upstreamStatus(): HttpStatusCode? = when (this) {
    is ResponseStatusException -> statusCode
    is WebClientResponseException -> statusCode
    else -> null
  }

  companion object {
    private val LOGGER = logger<OffenderService>()
  }
}
