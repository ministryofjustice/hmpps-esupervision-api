package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.MessageTemplateConfig
import uk.gov.service.notify.NotificationClientApi
import java.util.UUID

@Service
class GovNotifyNotificationService(
  private val notifyClient: NotificationClientApi,
  private val appConfig: AppConfig,
  private val notificationsConfig: MessageTemplateConfig,
) : NotificationService {
  override fun sendMessage(message: Message, recipient: Contactable) {
    val reference = UUID.randomUUID().toString()

    recipient.contactMethods().forEach {
      dispatchNotification(it, reference, message)
    }
  }

  fun dispatchNotification(method: NotificationMethod, reference: String, message: Message) {
    val templateId = this.notificationsConfig.templatesFor(method).getTemplate(message.messageType)
    val personalisation = message.personalisationData(this.appConfig)

    try {
      when (method) {
        is PhoneNumber -> {
          val smsResponse = this.notifyClient.sendSms(templateId, method.phoneNumber, personalisation, reference)
        }
        is Email -> {
          val emailResponse = this.notifyClient.sendEmail(templateId, method.email, personalisation, reference)
        }
      }
    } catch (ex: Exception) {
      // log failure and continue
      LOGGER.error("Failed to send notification message", ex)
    }
  }

  companion object {
    val LOGGER: Logger = LoggerFactory.getLogger(this::class.java)
  }
}
