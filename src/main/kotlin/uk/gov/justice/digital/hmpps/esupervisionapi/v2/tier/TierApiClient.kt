package uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import java.time.LocalDate
import java.util.*

interface ITierApiClient {
  fun getTierDetails(crn: String): TierDetails?
}

/**
 * Client for Tier API CURRENTLY WITHOUT circuit breaker and retry resilience patterns
 * Based on OpenAPI spec provided
 *
 * The one exception is authentication: `tierApiWebClient` carries a
 * [uk.gov.justice.digital.hmpps.esupervisionapi.config.RefreshTokenOnUnauthorizedFilter], so a 401
 * only reaches the catch below once a freshly minted token has also been rejected.
 */
@Profile("!stubtier")
@Service
class TierApiClient(
  private val tierApiWebClient: WebClient,
) : ITierApiClient {

  override fun getTierDetails(crn: String): TierDetails? {
    LOGGER.info("Fetching tier details for CRN: {}", crn)

    return try {
      tierApiWebClient.get()
        .uri("/v2/crn/{crn}/tier", crn)
        .retrieve()
        .bodyToMono(TierDetails::class.java)
        .block()
    } catch (e: WebClientResponseException.NotFound) {
      LOGGER.warn("Tier details not found for CRN: {}", crn)
      throw ResponseStatusException(HttpStatus.NOT_FOUND, "Could not find tier details in Tier API for $crn.", e)
    } catch (e: WebClientResponseException) {
      LOGGER.error("Error fetching tier details: {} {}", PiiSanitizer.sanitizeException(e, crn), describeResponse(e))
      if (e.statusCode.is4xxClientError) {
        throw ResponseStatusException(
          e.statusCode,
          "Could not verify tier details in Tier API for $crn.",
          e,
        )
      }
      throw ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Encountered an issue whilst retrieving the tier details in Tier API for $crn.",
      )
    } catch (e: Exception) {
      LOGGER.error("Error fetching tier details: {}", PiiSanitizer.sanitizeException(e, crn))
      throw e
    }
  }

  /**
   * The upstream's own account of why it rejected us.
   *
   * [WebClientResponseException.message] is only "401 Unauthorized from GET <url>", which cannot
   * tell an expired token apart from a request that went out with no Authorization header at all.
   * A resource server puts that distinction in `WWW-Authenticate`
   * (`error="invalid_token", error_description="Jwt expired at ..."`, or no header when no bearer
   * was presented), so log it alongside a truncated, sanitised body.
   */
  private fun describeResponse(e: WebClientResponseException): String {
    val challenge = e.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)
    val body = e.responseBodyAsString.take(MAX_LOGGED_BODY_CHARS)
    return listOfNotNull(
      challenge?.let { "wwwAuthenticate=$it" },
      body.ifBlank { null }?.let { "body=${PiiSanitizer.sanitizeMessage(it)}" },
    ).joinToString(" ").ifEmpty { "(no challenge or body)" }
  }

  companion object {
    private const val MAX_LOGGED_BODY_CHARS = 500
    private val LOGGER = LoggerFactory.getLogger(this::class.java)
  }
}

data class TierDetails(
  val tierScore: String,
  val calculationId: UUID,
  val calculationDate: LocalDate,
  val changeReason: String?,
)
