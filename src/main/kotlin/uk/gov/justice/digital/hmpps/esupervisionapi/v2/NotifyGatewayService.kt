package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.google.common.util.concurrent.RateLimiter
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import uk.gov.service.notify.NotificationClientApi
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
    val response = notifyClient.sendSms(templateId, phoneNumber, personalisation, reference)
    LOGGER.debug("SMS sent successfully: notificationId={}, reference={}", response.notificationId, reference)
    return response.notificationId
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
    val response = notifyClient.sendEmail(templateId, emailAddress, personalisation, reference)
    LOGGER.debug("Email sent successfully: notificationId={}, reference={}", response.notificationId, reference)
    return response.notificationId
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

  companion object {
    private val LOGGER = LoggerFactory.getLogger(NotifyGatewayService::class.java)
  }
}
