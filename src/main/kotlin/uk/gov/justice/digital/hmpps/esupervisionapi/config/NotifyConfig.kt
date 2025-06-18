package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.GovNotifyNotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.service.notify.NotificationClient

@Configuration
class NotifyConfig(
  @Value("\${app.hostedAt}") val hostedAt: String,
  @Value("\${notify.apiKey}") val apiKey: String,
  @Value("\${notify.offenderInviteSmsTemplateId}") val inviteSMSTemplateId: String,
  @Value("\${notify.offenderInviteEmailTemplateId}") val inviteEmailTemplateId: String,
) {
  @Bean
  fun notificationService(): NotificationService {
    val client = NotificationClient(apiKey)
    return GovNotifyNotificationService(
      hostedAt,
      offenderInviteEmailTemplateId = inviteEmailTemplateId,
      offenderInviteSMSTemplateId = inviteSMSTemplateId,
      notifyClient = client,
    )
  }
}
