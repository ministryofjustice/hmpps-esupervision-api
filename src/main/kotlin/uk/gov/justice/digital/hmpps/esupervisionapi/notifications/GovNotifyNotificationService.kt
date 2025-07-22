package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.MessageTemplateConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.NotificationResultSummary
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.NotificationResults
import uk.gov.service.notify.NotificationClientApi
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Service
class GovNotifyNotificationService(
  private val clock: Clock,
  private val notifyClient: NotificationClientApi,
  private val appConfig: AppConfig,
  private val notificationsConfig: MessageTemplateConfig,
) : NotificationService {
  override fun sendMessage(message: Message, recipient: Contactable): NotificationResults {
    val reference = UUID.randomUUID().toString()
    val notificationRefs = recipient.contactMethods().mapNotNull {
      dispatchNotification(it, reference, message)
    }
    return notificationResults(clock.instant(), notificationRefs)
  }

  fun dispatchNotification(method: NotificationMethod, reference: String, message: Message): NotificationReference? {
    val templateId = this.notificationsConfig.templatesFor(method).getTemplate(message.messageType)
    val personalisation = message.personalisationData(this.appConfig)

    try {
      when (method) {
        is PhoneNumber -> {
          val smsResponse = this.notifyClient.sendSms(templateId, method.phoneNumber, personalisation, reference)
          return NotificationReference(method.method, smsResponse.notificationId)
        }
        is Email -> {
          val emailResponse = this.notifyClient.sendEmail(templateId, method.email, personalisation, reference)
          return NotificationReference(method.method, emailResponse.notificationId)
        }
      }
    } catch (ex: Exception) {
      // log failure and continue
      LOGGER.error("Failed to send notification message", ex)
    }
    return null
  }

  companion object {
    val LOGGER: Logger = LoggerFactory.getLogger(this::class.java)
  }
}

private fun notificationResults(
  now: Instant,
  inviteReferences: List<NotificationReference>,
): NotificationResults {
  val groupedInviteRefs = inviteReferences.groupBy { it.method }
  val notificationTime = now.atZone(ZoneId.of("UTC"))
  val phoneNotification = groupedInviteRefs.get(NotificationMethodKey.PHONE)?.first()
  val emailNotification = groupedInviteRefs.get(NotificationMethodKey.EMAIL)?.first()

  val phoneNotificationSummary = if (phoneNotification != null) {
    NotificationResultSummary(
      phoneNotification.notificationId,
      notificationTime,
      null,
      null,
    )
  } else {
    null
  }
  val emailNotificationSummary = if (emailNotification != null) {
    NotificationResultSummary(
      emailNotification.notificationId,
      notificationTime,
      null,
      null,
    )
  } else {
    null
  }

  return NotificationResults(listOfNotNull(phoneNotificationSummary, emailNotificationSummary))
}
