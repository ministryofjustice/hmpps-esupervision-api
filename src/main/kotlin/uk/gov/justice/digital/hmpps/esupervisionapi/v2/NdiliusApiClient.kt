package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import io.micrometer.core.annotation.Timed
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer

class NdiliusBatchFetchException(val crns: List<CRN>, message: String, cause: Exception) : RuntimeException(message, cause)

/**
 * Wire shape expected by esupervision-and-delius's PUT /case/{crn}/contact-details (PI-4356).
 * Field names (`mobileNumber`/`emailAddress`) match their `UpdateContactDetails` request DTO,
 * which differs from our own [ContactDetailsUpdateRequest]'s `mobile`/`email` naming.
 */
private data class NdiliusContactDetailsUpdateBody(
  val mobileNumber: String?,
  val emailAddress: String?,
)

/**
 * Wire shape returned by esupervision-and-delius's GET /user/{username}/alerts.
 */
private data class NdiliusAlertsResponse(
  val count: Int,
)

interface INdiliusApiClient {
  fun validatePersonalDetails(personalDetails: PersonalDetails): Boolean

  /**
   * Get contact details by CRN. Returns null if not found.
   */
  fun getContactDetails(crn: String): ContactDetails?
  fun getContactDetailsForMultiple(crns: List<String>): List<ContactDetails>

  /**
   * Update a person's contact details by CRN.
   * NOTE: depends on PI-4356 (esupervision-and-delius PUT /case/{crn}/contact-details), not yet live.
   */
  fun updateContactDetails(crn: String, request: ContactDetailsUpdateRequest): ContactDetailsUpdateResponse

  /**
   * Get the number of alerts for a practitioner by NDelius username.
   * Returns null if the username is not found.
   */
  fun getAlertCount(username: String): Int?

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
  @Autowired
  @Lazy
  private lateinit var self: INdiliusApiClient

  /**
   * Get contact details for a single person on probation by CRN
   * GET /case/{crn}
   */
  @CircuitBreaker(name = "ndiliusApi", fallbackMethod = "getContactDetailsFallback")
  @Retry(name = "ndiliusApi")
  @Timed("ndelius.get-contact-details", extraTags = ["method", "GET", "endpoint", "/case/{crn}"], description = "Time taken to get contact details")
  override fun getContactDetails(crn: String): ContactDetails? {
    LOGGER.info("Fetching contact details for CRN: {}", crn)

    return try {
      ndiliusApiWebClient.get()
        .uri("/case/{crn}", crn)
        .retrieve()
        .bodyToMono(ContactDetails::class.java)
        .block()
    } catch (e: WebClientResponseException.NotFound) {
      LOGGER.warn("Contact details not found for CRN: {}", crn)
      null
    } catch (e: WebClientResponseException) {
      LOGGER.warn("Error fetching contact details: {}", PiiSanitizer.sanitizeException(e, crn))
      if (e.statusCode.is4xxClientError) {
        throw ResponseStatusException(
          e.statusCode,
          "Could not verify contact details in NDelius for $crn.",
          e,
        )
      }
      throw ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Encountered an issue whilst retrieving the contact details in NDelius for $crn.",
      )
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
  @Timed("ndelius.get-contact-details-for-multiple", extraTags = ["method", "POST", "endpoint", "/cases"], description = "Time taken to get contact details")
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

  private fun getContactDetailsForMultipleFallback(crns: List<String>?, e: Exception): List<ContactDetails> {
    LOGGER.error("Circuit breaker activated: {}", PiiSanitizer.sanitizeForFallback(e, "getContactDetailsForMultiple, batchSize=${crns?.size}"))
    return emptyList()
  }

  /**
   * Update contact details for a person on probation by CRN
   * PUT /case/{crn}/contact-details
   * NDelius's endpoint is a full overwrite, not a partial update: any field omitted from the
   * request body is persisted as empty, wiping the existing value. So if the caller only
   * supplied one of mobile/email, we fetch the current record first and fill in the other
   * field from it before sending, to avoid silently deleting it.
   */
  @CircuitBreaker(name = "ndiliusApi", fallbackMethod = "updateContactDetailsFallback")
  @Retry(name = "ndiliusApi")
  @Timed("ndelius.update-contact-details", extraTags = ["method", "PUT", "endpoint", "/case/{crn}/contact-details"], description = "Time taken to update contact details")
  override fun updateContactDetails(crn: String, request: ContactDetailsUpdateRequest): ContactDetailsUpdateResponse {
    LOGGER.info("Updating contact details for CRN: {} requested by practitioner: {}", crn, request.practitionerId)

    return try {
      val merged = if (request.mobile == null || request.email == null) {
        val existing = self.getContactDetails(crn)
        ContactDetailsUpdateRequest(
          practitionerId = request.practitionerId,
          mobile = request.mobile ?: existing?.mobile,
          email = request.email ?: existing?.email,
        )
      } else {
        request
      }

      ndiliusApiWebClient.put()
        .uri("/case/{crn}/contact-details", crn)
        .bodyValue(NdiliusContactDetailsUpdateBody(mobileNumber = merged.mobile, emailAddress = merged.email))
        .retrieve()
        .toBodilessEntity()
        .block()

      ContactDetailsUpdateResponse(crn = crn, mobile = merged.mobile, email = merged.email)
    } catch (e: WebClientResponseException.NotFound) {
      LOGGER.warn("Contact details not found for CRN: {}", crn)
      throw ResponseStatusException(HttpStatus.NOT_FOUND, "Contact details not found in NDelius for $crn.", e)
    } catch (e: WebClientResponseException) {
      LOGGER.warn("Error updating contact details: {}", PiiSanitizer.sanitizeException(e, crn))
      if (e.statusCode.is4xxClientError) {
        throw ResponseStatusException(e.statusCode, "Could not update contact details in NDelius for $crn.", e)
      }
      throw ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Encountered an issue whilst updating the contact details in NDelius for $crn.",
      )
    } catch (e: Exception) {
      LOGGER.error("Error updating contact details: {}", PiiSanitizer.sanitizeException(e, crn))
      throw e
    }
  }

  private fun updateContactDetailsFallback(crn: String, request: ContactDetailsUpdateRequest, e: Exception): ContactDetailsUpdateResponse {
    LOGGER.error("Circuit breaker activated: {}", PiiSanitizer.sanitizeForFallback(e, "updateContactDetails, crn=$crn"))
    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Encountered an issue whilst updating the contact details in NDelius for $crn.")
  }

  /**
   * Validate personal details for a person on probation
   * POST /case/{crn}/validate-details
   * Returns true if valid (200 OK), false if invalid (400 Bad Request)
   */
  @CircuitBreaker(name = "ndiliusApi", fallbackMethod = "validatePersonalDetailsFallback")
  @Retry(name = "ndiliusApi")
  @Timed("ndelius.validate-details", extraTags = ["method", "POST", "endpoint", "/case/{crn}/validate-details"], description = "Time taken to validate personal details")
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

  /**
   * Get the number of alerts for a practitioner by NDelius username
   * GET /user/{username}/alerts
   */
  @CircuitBreaker(name = "ndiliusApi", fallbackMethod = "getAlertCountFallback")
  @Retry(name = "ndiliusApi")
  @Timed("ndelius.get-alert-count", extraTags = ["method", "GET", "endpoint", "/user/{username}/alerts"], description = "Time taken to get alert count")
  override fun getAlertCount(username: String): Int? {
    LOGGER.info("Fetching alert count for username: {}", username)

    return try {
      ndiliusApiWebClient.get()
        .uri("/user/{username}/alerts", username)
        .retrieve()
        .bodyToMono(NdiliusAlertsResponse::class.java)
        .block()
        ?.count ?: 0
    } catch (e: WebClientResponseException.NotFound) {
      LOGGER.warn("Alerts not found for username: {}", username)
      null
    } catch (e: WebClientResponseException) {
      LOGGER.warn("Error fetching alert count: {}", PiiSanitizer.sanitizeException(e, username))
      if (e.statusCode.is4xxClientError) {
        throw ResponseStatusException(e.statusCode, "Could not fetch alerts in NDelius for $username.", e)
      }
      throw ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Encountered an issue whilst fetching alerts in NDelius for $username.",
      )
    } catch (e: Exception) {
      LOGGER.error("Error fetching alert count: {}", PiiSanitizer.sanitizeException(e, username))
      throw e
    }
  }

  private fun getAlertCountFallback(username: String, e: Exception): Int? {
    LOGGER.error("Circuit breaker activated: {}", PiiSanitizer.sanitizeForFallback(e, "getAlertCount, username=$username"))
    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Encountered an issue whilst fetching alerts in NDelius for $username.")
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(this::class.java)
  }
}
