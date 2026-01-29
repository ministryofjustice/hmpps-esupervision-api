package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer

class NdiliusBatchFetchException(val crns: List<CRN>, message: String, cause: Exception) : RuntimeException(message, cause)

interface INdiliusApiClient {
  fun validatePersonalDetails(personalDetails: PersonalDetails): Boolean
  fun getContactDetails(crn: String): ContactDetails?
  fun getContactDetailsForMultiple(crns: List<String>): List<ContactDetails>

  companion object {
    const val MAX_BATCH_SIZE = 500
  }
}

/**
 * Client for Ndilius API with circuit breaker and retry resilience patterns
 * Based on OpenAPI spec provided
 */
@Profile("!stubndilius")
@Service
class NdiliusApiClient(
  private val ndiliusApiWebClient: WebClient,
) : INdiliusApiClient {
  /**
   * Get contact details for a single person on probation by CRN
   * GET /case/{crn}
   */
  @CircuitBreaker(name = "ndiliusApi", fallbackMethod = "getContactDetailsFallback")
  @Retry(name = "ndiliusApi")
  override fun getContactDetails(crn: String): ContactDetails? {
    LOGGER.info("Fetching contact details for CRN: {}", crn)

    return try {
      ndiliusApiWebClient.get()
        .uri("/case/{crn}", crn)
        .retrieve()
        .bodyToMono(ContactDetails::class.java)
        .block()
    } catch (e: WebClientResponseException.NotFound) {
      LOGGER.error("Contact details not found for CRN: {}", crn)
      null
    } catch (e: Exception) {
      LOGGER.error("Error fetching contact details: {}", PiiSanitizer.sanitizeException(e, crn))
      throw e
    }
  }

  private fun getContactDetailsFallback(crn: String, e: Exception): ContactDetails? {
    LOGGER.error("Circuit breaker activated: {}", PiiSanitizer.sanitizeForFallback(e, "getContactDetails, crn=$crn"))
    return null
  }

  /**
   * Get contact details for multiple people on probation (max 500 CRNs)
   * POST /cases
   * @throws NdiliusBatchFetchException
   */
  @CircuitBreaker(name = "ndiliusApi", fallbackMethod = "getContactDetailsForMultipleFallback")
  @Retry(name = "ndiliusApi")
  override fun getContactDetailsForMultiple(crns: List<String>): List<ContactDetails> {
    if (crns.isEmpty()) {
      return emptyList()
    }

    if (crns.size > INdiliusApiClient.MAX_BATCH_SIZE) {
      LOGGER.warn("Batch size {} exceeds maximum of {}, truncating", crns.size, INdiliusApiClient.MAX_BATCH_SIZE)
    }

    val batchCrns = crns.take(INdiliusApiClient.MAX_BATCH_SIZE)
    LOGGER.info("Fetching contact details for {} CRNs in batch", batchCrns.size)

    return try {
      ndiliusApiWebClient.post()
        .uri("/cases")
        .bodyValue(batchCrns)
        .retrieve()
        .bodyToFlux(ContactDetails::class.java)
        .collectList()
        .block() ?: emptyList()
    } catch (e: Exception) {
      LOGGER.warn("Error fetching contact details for batch: {}", PiiSanitizer.sanitizeMessage(e.message ?: "Unknown error", null, null) + " [batchSize=${batchCrns.size}]")
      throw NdiliusBatchFetchException(crns, "Error fetching contact details", e)
    }
  }

  private fun getContactDetailsForMultipleFallback(crns: List<String>, e: Exception): List<ContactDetails> {
    LOGGER.error("Circuit breaker activated: {}", PiiSanitizer.sanitizeForFallback(e, "getContactDetailsForMultiple, batchSize=${crns.size}"))
    return emptyList()
  }

  /**
   * Get contact details in batches (handles automatic chunking)
   */
  fun getContactDetailsInBatches(crns: List<String>): List<ContactDetails> {
    if (crns.isEmpty()) {
      return emptyList()
    }

    LOGGER.info("Fetching contact details for {} CRNs in batches of {}", crns.size, INdiliusApiClient.MAX_BATCH_SIZE)

    return crns.chunked(INdiliusApiClient.MAX_BATCH_SIZE).flatMap { batch ->
      getContactDetailsForMultiple(batch)
    }
  }

  /**
   * Validate personal details for a person on probation
   * POST /case/{crn}/validate-details
   * Returns true if valid (200 OK), false if invalid (400 Bad Request)
   */
  @CircuitBreaker(name = "ndiliusApi", fallbackMethod = "validatePersonalDetailsFallback")
  @Retry(name = "ndiliusApi")
  override fun validatePersonalDetails(personalDetails: PersonalDetails): Boolean {
    LOGGER.info("Validating personal details for CRN: {}", personalDetails.crn)

    return try {
      ndiliusApiWebClient.post()
        .uri("/case/{crn}/validate-details", personalDetails.crn)
        .bodyValue(personalDetails)
        .retrieve()
        .toBodilessEntity()
        .block()

      LOGGER.info("Personal details validated successfully for CRN: {}", personalDetails.crn)
      true
    } catch (e: WebClientResponseException) {
      if (e.statusCode == HttpStatus.BAD_REQUEST) {
        LOGGER.info("Personal details validation failed for CRN: {}", personalDetails.crn)
        false
      } else {
        LOGGER.error("Unexpected error validating personal details: {}", PiiSanitizer.sanitizeException(e, personalDetails.crn))
        throw e
      }
    }
  }

  private fun validatePersonalDetailsFallback(personalDetails: PersonalDetails, e: Exception): Boolean {
    LOGGER.error("Circuit breaker activated: {}", PiiSanitizer.sanitizeForFallback(e, "validatePersonalDetails, crn=${personalDetails.crn}"))
    return false
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(this::class.java)
  }
}
