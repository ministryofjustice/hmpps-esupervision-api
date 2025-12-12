package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.boot.context.properties.ConfigurationProperties
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Email
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationMethod
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PhoneNumber

@ConfigurationProperties(prefix = "notify")
class MessageTemplateConfig(
  private val smsTemplates: MessageTypeTemplateConfig,
  private val emailTemplates: MessageTypeTemplateConfig,
  val channels: NotificationChannelsConfig,
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

class NotificationChannelsConfig(
  val offenderSmsEnabled: Boolean,
  val offenderEmailEnabled: Boolean,
  val practitionerSmsEnabled: Boolean,
  val practitionerEmailEnabled: Boolean,
)
