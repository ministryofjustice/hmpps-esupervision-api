package uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import java.time.LocalDate

interface IArnsApiClient {
  fun getRiskWidget(crn: String): ArnsWidget?
}

/**
 * Client for ARNS API CURRENTLY WITHOUT circuit breaker and retry resilience patterns
 * Based on OpenAPI spec provided
 */
@Profile("!stubarns")
@Service
class ArnsApiClient(
  private val arnsApiWebClient: WebClient,
) : IArnsApiClient {

  override fun getRiskWidget(crn: String): ArnsWidget? {
    LOGGER.info("Fetching risk widget for CRN: {}", crn)

    return try {
      arnsApiWebClient.get()
        .uri("/risks/crn/{crn}/widget", crn)
        .retrieve()
        .bodyToMono(ArnsWidget::class.java)
        .block()
    } catch (e: WebClientResponseException.NotFound) {
      LOGGER.warn("Risk widget not found for CRN: {}", crn)
      return ArnsWidget()
    } catch (e: Exception) {
      LOGGER.error("Error fetching risk widget: {}", PiiSanitizer.sanitizeException(e, crn))
      throw e
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(this::class.java)
  }
}

data class ArnsWidget(
  val overallRisk: String,
  val assessedOn: LocalDate?,
  val riskInCommunity: RiskInSituation,
  val riskInCustody: RiskInSituation,
) {
  constructor() : this(
    "NOT_FOUND",
    null,
    RiskInSituation(),
    RiskInSituation(),
  )
}

data class RiskInSituation(
  val public: String?,
  val children: String?,
  val knownAdult: String?,
  val staff: String?,
  val prisoners: String?,
) {
  constructor() : this(
    "NOT_FOUND",
    "NOT_FOUND",
    "NOT_FOUND",
    "NOT_FOUND",
    "NOT_FOUND",
  )
}
