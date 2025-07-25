package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.MessageTemplateConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.NotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.NotificationResultSummary
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.NotificationResults
import uk.gov.service.notify.NotificationClientApi
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

enum class NotificationMethodKey {
  PHONE,
  EMAIL,
}

@Service
class GovNotifyNotificationService(
  private val clock: Clock,
  private val notifyClient: NotificationClientApi,
  private val appConfig: AppConfig,
  private val notificationsConfig: MessageTemplateConfig,
) : NotificationService {

  override fun sendMessage(message: Message, recipient: Contactable, context: NotificationContext): NotificationResults {
    val now = clock.instant()
    val notificationRefs = recipient.contactMethods().mapNotNull {
      dispatchNotification(it, context, message, now)
    }
    return NotificationResults(notificationRefs)
  }

  fun dispatchNotification(method: NotificationMethod, context: NotificationContext, message: Message, now: Instant): NotificationResultSummary? {
    val templateId = this.notificationsConfig.templatesFor(method).getTemplate(message.messageType)
    val personalisation = message.personalisationData(this.appConfig)
    try {
      return method.notify(this.notifyClient, context, now, templateId, personalisation)
    } catch (ex: Exception) {
      // log failure and continue
      LOGGER.warn("Failed to send notification message", ex)
    }
    return null
  }

  companion object {
    val LOGGER: Logger = LoggerFactory.getLogger(this::class.java)
  }
}

private fun NotificationMethod.notify(
  client: NotificationClientApi,
  context: NotificationContext,
  now: Instant,
  templateId: String,
  personalisation: Map<String, Any?>,
): NotificationResultSummary = when (this) {
  is PhoneNumber -> this.notify(client, context, now, templateId, personalisation)
  is Email -> this.notify(client, context, now, templateId, personalisation)
}

private fun PhoneNumber.notify(
  client: NotificationClientApi,
  context: NotificationContext,
  now: Instant,
  templateId: String,
  personalisation: Map<String, Any?>,
): NotificationResultSummary {
  val response = client.sendSms(templateId, phoneNumber, personalisation, context.reference)
  return NotificationResultSummary(
    response.notificationId,
    context,
    now.atZone(ZoneId.of("UTC")),
    null,
    null,
  )
}

private fun Email.notify(
  client: NotificationClientApi,
  context: NotificationContext,
  now: Instant,
  templateId: String,
  personalisation: Map<String, Any?>,
): NotificationResultSummary {
  val response = client.sendEmail(templateId, email, personalisation, context.reference)
  return NotificationResultSummary(
    response.notificationId,
    context,
    now.atZone(ZoneId.of("UTC")),
    null,
    null,
  )
}
