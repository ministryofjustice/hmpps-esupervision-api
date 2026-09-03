package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.IArnsApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.ITierApiClient
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Service
class OffenderService(
  private val ndiliusApiClient: INdiliusApiClient,
  private val tierApiClient: ITierApiClient,
  private val arnsApiClient: IArnsApiClient,
  @Value("\${api.base.url.tier-ui}") val tierUiBaseUri: String,
) : DisposableBean {

  /**
   * Runs the Tier lookup alongside the ARNS lookup so a slow upstream costs the request
   * max(tier, arns) rather than the sum. Virtual threads: each task just blocks on a WebClient
   * call, and the number in flight is bounded by the servlet thread pool.
   */
  private val lookupExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

  override fun destroy() {
    lookupExecutor.shutdownNow()
  }

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

    val tierLookup = lookupExecutor.submit(
      Callable { fetchField("tierScore", "Tier API", crn) { tierApiClient.getTierDetails(crn)?.tierScore } },
    )
    val risk = fetchField("overallRisk", "ARNS API", crn) { arnsApiClient.getRiskWidget(crn)?.overallRisk }
    val tier = tierLookup.get()

    return OffenderHeaderDetails(
      crn = crn,
      dateOfBirth = contact.value?.dateOfBirth,
      tierScore = tier.value,
      tierDetailsLink = "$tierUiBaseUri/case/$crn",
      overallRisk = risk.value,
      errors = listOfNotNull(contact.toErrorDetails(), tier.toErrorDetails(), risk.toErrorDetails()),
    )
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
    // The clients log their own failures; this records the classification with the stack trace
    // that would otherwise be lost, since the exception goes no further.
    LOGGER.warn("Failed to fetch {} from {} ({}): {}", field, source, code, PiiSanitizer.sanitizeException(e, crn), e)
    FieldResult(field, null, code)
  }

  private fun classify(e: Exception): HeaderErrorCode = when {
    e is ResponseStatusException && e.statusCode == HttpStatus.NOT_FOUND -> HeaderErrorCode.NOT_FOUND
    e is ResponseStatusException && e.statusCode.is4xxClientError -> HeaderErrorCode.REQUEST_REJECTED
    else -> HeaderErrorCode.SERVICE_UNAVAILABLE
  }

  companion object {
    private val LOGGER = logger<OffenderService>()
  }
}
