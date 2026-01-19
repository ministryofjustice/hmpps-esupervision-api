package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.google.common.util.concurrent.RateLimiter
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import uk.gov.service.notify.NotificationClientApi
import uk.gov.service.notify.NotificationClientException
import java.util.UUID

/**
 * Service responsible for all interactions with GOV.UK Notify
 * Handles rate limiting, circuit breaking, and retry logic
 */
@Service
class NotifyGatewayService(
  private val notifyClient: NotificationClientApi,
) {
  // Rate limiting: 3000 requests/minute = 45/sec with safety margin
  private val rateLimiter = RateLimiter.create(45.0)

  /**
   * Send SMS via GOV.UK Notify with rate limiting and resilience
   */
  @CircuitBreaker(name = "govNotify", fallbackMethod = "sendSmsFallback")
  @Retry(name = "govNotify")
  fun sendSms(
    templateId: String,
    phoneNumber: String,
    personalisation: Map<String, String>,
    reference: String,
  ): UUID {
    rateLimiter.acquire()
    try {
      val response = notifyClient.sendSms(templateId, phoneNumber, personalisation, reference)
      LOGGER.debug("SMS sent successfully: notificationId={}, reference={}", response.notificationId, reference)
      return response.notificationId
    } catch (e: NotificationClientException) {
      handleNotifyException(e, "SMS", templateId, reference)
      throw e
    }
  }

  /**
   * Send email via GOV.UK Notify with rate limiting and resilience
   */
  @CircuitBreaker(name = "govNotify", fallbackMethod = "sendEmailFallback")
  @Retry(name = "govNotify")
  fun sendEmail(
    templateId: String,
    emailAddress: String,
    personalisation: Map<String, String>,
    reference: String,
  ): UUID {
    rateLimiter.acquire()
    try {
      val response = notifyClient.sendEmail(templateId, emailAddress, personalisation, reference)
      LOGGER.debug("Email sent successfully: notificationId={}, reference={}", response.notificationId, reference)
      return response.notificationId
    } catch (e: NotificationClientException) {
      handleNotifyException(e, "EMAIL", templateId, reference)
      throw e
    }
  }

  /**
   * Generic send method that routes to appropriate channel
   */
  fun send(
    channel: String,
    templateId: String,
    recipient: String,
    personalisation: Map<String, String>,
    reference: String,
  ): UUID = when (channel) {
    "SMS" -> sendSms(templateId, recipient, personalisation, reference)
    "EMAIL" -> sendEmail(templateId, recipient, personalisation, reference)
    else -> throw IllegalArgumentException("Unknown notification channel: $channel")
  }

  // Circuit breaker fallback methods
  private fun sendSmsFallback(
    templateId: String,
    phoneNumber: String,
    personalisation: Map<String, String>,
    reference: String,
    e: Exception,
  ): UUID {
    LOGGER.error("Circuit breaker activated for SMS: {}", PiiSanitizer.sanitizeForFallback(e, "reference=$reference"))
    throw e
  }

  private fun sendEmailFallback(
    templateId: String,
    emailAddress: String,
    personalisation: Map<String, String>,
    reference: String,
    e: Exception,
  ): UUID {
    LOGGER.error("Circuit breaker activated for email: {}", PiiSanitizer.sanitizeForFallback(e, "reference=$reference"))
    throw e
  }

  /**
   * Handle GOV.UK Notify exceptions with specific logging for template errors.
   * GOV.UK Notify returns 400 Bad Request for invalid/non-existent template IDs.
   */
  private fun handleNotifyException(
    e: NotificationClientException,
    channel: String,
    templateId: String,
    reference: String,
  ) {
    val message = e.message ?: ""
    val isTemplateError = e.httpResult == 400 &&
      (
        message.contains("template", ignoreCase = true) ||
          message.contains("not found", ignoreCase = true) ||
          message.contains("ValidationError", ignoreCase = true)
        )

    if (isTemplateError) {
      LOGGER.error(
        "INVALID_TEMPLATE: {} notification failed - template ID '{}' is invalid or does not exist in GOV.UK Notify. " +
          "reference={}, httpStatus={}, error={}",
        channel,
        templateId,
        reference,
        e.httpResult,
        PiiSanitizer.sanitizeMessage(message, null, null),
      )
    } else {
      LOGGER.warn(
        "GOV.UK Notify {} failed: reference={}, templateId={}, httpStatus={}, error={}",
        channel,
        reference,
        templateId,
        e.httpResult,
        PiiSanitizer.sanitizeMessage(message, null, null),
      )
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(NotifyGatewayService::class.java)
  }
}
