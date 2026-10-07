package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.google.common.util.concurrent.RateLimiter
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import uk.gov.service.notify.NotificationClientApi
import uk.gov.service.notify.NotificationClientException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.UUID
import java.util.function.Predicate

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
   * This bean's own proxy. [send] must delegate through it: a call on `this` bypasses Spring AOP,
   * so sendSms/sendEmail would run with no retry or circuit breaker.
   */
  @Autowired
  @Lazy
  private lateinit var self: NotifyGatewayService

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
    "SMS" -> self.sendSms(templateId, recipient, personalisation, reference)
    "EMAIL" -> self.sendEmail(templateId, recipient, personalisation, reference)
    else -> throw IllegalArgumentException("Unknown notification channel: $channel")
  }

  // Circuit breaker fallback methods. Typed to CallNotPermittedException so they run only for an
  // open circuit: resilience4j rethrows unchanged anything a fallback's parameter type does not
  // match, and every other failure is already logged by handleNotifyException.
  private fun sendSmsFallback(
    templateId: String,
    phoneNumber: String,
    personalisation: Map<String, String>,
    reference: String,
    e: CallNotPermittedException,
  ): UUID {
    LOGGER.error("Circuit breaker activated for SMS: {}", PiiSanitizer.sanitizeForFallback(e, "reference=$reference"))
    throw e
  }

  private fun sendEmailFallback(
    templateId: String,
    emailAddress: String,
    personalisation: Map<String, String>,
    reference: String,
    e: CallNotPermittedException,
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

/**
 * Which GOV.UK Notify failures the `govNotify` circuit breaker records (see application.yml).
 *
 * Transient ones only: no HTTP status (a network/IO failure), 429 rate limiting, and 5xx. A 4xx is a
 * problem with this request - an invalid phone number, email address or template - and recording it
 * would let a run of bad recipient data open the breaker and block every notification.
 */
class NotifyTransientFailure : Predicate<Throwable> {
  override fun test(t: Throwable): Boolean = t is NotificationClientException &&
    (t.httpResult == 0 || t.httpResult == 429 || t.httpResult >= 500)
}

/**
 * Which GOV.UK Notify failures the `govNotify` retry resends (see application.yml).
 *
 * Narrower than [NotifyTransientFailure]: only failures where Notify cannot have created the
 * notification. Notify has no idempotency key - every POST is a new notification - so retrying a
 * request it may have accepted (a read timeout or reset after sending, a 502/504 from its gateway)
 * risks sending the same SMS or email twice.
 * - 429: rate limited, rejected before processing.
 * - 500: Notify documents this as "unable to process the request, resend your notification".
 * - 503: unavailable, rejected before processing.
 * - No status, caused by a failure to connect: the request was never sent.
 */
class NotifyRetryableFailure : Predicate<Throwable> {
  override fun test(t: Throwable): Boolean {
    if (t !is NotificationClientException) return false
    return when (t.httpResult) {
      429, 500, 503 -> true
      0 -> generateSequence(t.cause) { it.cause }.any { it is ConnectException || it is NoRouteToHostException || it is UnknownHostException }
      else -> false
    }
  }
}
