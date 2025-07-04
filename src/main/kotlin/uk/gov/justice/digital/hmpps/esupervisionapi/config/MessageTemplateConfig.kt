package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.boot.context.properties.ConfigurationProperties
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Email
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationMethod
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PhoneNumber

@ConfigurationProperties(prefix = "notify")
class MessageTemplateConfig(
  private val smsTemplates: MessageTypeTemplateConfig,
  private val emailTemplates: MessageTypeTemplateConfig,
) {
  fun templatesFor(method: NotificationMethod) = when (method) {
    is PhoneNumber -> {
      smsTemplates
    }
    is Email -> {
      emailTemplates
    }
  }
}
