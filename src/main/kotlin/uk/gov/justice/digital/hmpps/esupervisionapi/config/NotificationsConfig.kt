package uk.gov.justice.digital.hmpps.esupervisionapi.config
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Email
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationMethod
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PhoneNumber

class NotificationsConfig(
  private val emailTemplates: Map<String, String>,
  private val smsTemplates: Map<String, String>,
) {
  fun getTemplateId(notificationMethod: NotificationMethod, templateName: String): String {
    val templates = templatesFor(notificationMethod)
    val templateId = templates.get(templateName)

    if (templateId == null) {
      throw IllegalArgumentException("Template '$templateName' does not exist")
    } else {
      return templateId
    }
  }

  fun templatesFor(notificationMethod: NotificationMethod): Map<String, String> {
    when (notificationMethod) {
      is Email -> {
        return emailTemplates
      }
      is PhoneNumber -> {
        return smsTemplates
      }
    }
  }
}
