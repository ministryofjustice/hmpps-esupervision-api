package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.GovNotifyNotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.service.notify.NotificationClient
import uk.gov.service.notify.NotificationClientApi

@Configuration
class NotifyConfig(
  @Value("\${app.hostedAt}") val hostedAt: String,
  @Value("\${notify.apiKey}") val apiKey: String,
  @Value("\${notify.sms-templates.POP_CHECKIN_INVITE}") val checkinInviteSmsTemplate: String,
  @Value("\${notify.sms-templates.PRACTITIONER_CHECKIN_SUBMITTED}") val checkinSubmittedSmsTemplate: String,
  @Value("\${notify.email-templates.POP_CHECKIN_INVITE}") val checkinInviteEmailTemplate: String,
  @Value("\${notify.email-templates.PRACTITIONER_CHECKIN_SUBMITTED}") val checkinSubmittedEmailTemplate: String,
) {
  @Bean
  fun notificationClient(): NotificationClientApi = NotificationClient(apiKey)

  @Bean
  fun appConfig() = AppConfig(hostedAt)

  @Bean
  fun notificationsConfig() = NotificationsConfig(
    emailTemplates = mapOf(
      "POP_CHECKIN_INVITE" to checkinInviteEmailTemplate,
      "PRACTITIONER_CHECKIN_SUBMITTED" to checkinSubmittedEmailTemplate,
    ),
    smsTemplates = mapOf(
      "POP_CHECKIN_INVITE" to checkinInviteSmsTemplate,
      "PRACTITIONER_CHECKIN_SUBMITTED" to checkinSubmittedSmsTemplate,
    ),
  )

  @Bean
  fun notificationService(): NotificationService = GovNotifyNotificationService(
    notifyClient = this.notificationClient(),
    appConfig = this.appConfig(),
    notificationsConfig = this.notificationsConfig(),
  )
}
