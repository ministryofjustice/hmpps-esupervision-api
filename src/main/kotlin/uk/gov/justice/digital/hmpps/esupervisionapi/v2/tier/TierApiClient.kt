package uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import java.time.LocalDate
import java.util.*

interface ITierApiClient {
  fun getTierDetails(crn: String): TierDetails?
}

/**
 * Client for Tier API CURRENTLY WITHOUT circuit breaker and retry resilience patterns
 * Based on OpenAPI spec provided
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
      null
    } catch (e: Exception) {
      LOGGER.error("Error fetching tier details: {}", PiiSanitizer.sanitizeException(e, crn))
      throw e
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(this::class.java)
  }
}

data class TierDetails(
  val tierScore: String,
  val calculationId: UUID,
  val calculationDate: LocalDate,
  val changeReason: String?,
)
