package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.service.notify.NotificationClientApi
import java.util.UUID

class GovNotifyNotificationService(
  private val hostedAt: String,
  private val offenderInviteEmailTemplateId: String,
  private val offenderInviteSMSTemplateId: String,
  private val notifyClient: NotificationClientApi,
) : NotificationService {
  override fun notifyOffenderInvite(
    method: NotificationMethod,
    invite: OffenderInviteMessage,
  ) {
    val reference = UUID.randomUUID().toString()
    val personalisation = invite.personalisationData(this.hostedAt)

    when (method) {
      is PhoneNumber -> {
        val smsResponse = this.notifyClient.sendSms(this.offenderInviteSMSTemplateId, method.phoneNumber, personalisation, reference)
      }
      is Email -> {
        val emailResponse = this.notifyClient.sendEmail(this.offenderInviteEmailTemplateId, method.email, personalisation, reference)
      }
    }
  }
}
