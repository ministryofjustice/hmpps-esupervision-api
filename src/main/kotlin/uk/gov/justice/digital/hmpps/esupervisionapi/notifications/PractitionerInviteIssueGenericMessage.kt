package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin

data class PractitionerInviteIssueGenericMessage(
  val practitionerFirstName: String,
  val practitionerLastName: String,
  val offenderFirstName: String,
  val offenderLastName: String,
) : Message {
  override fun personalisationData(appConfig: AppConfig): Map<String, String> = mapOf(
    "practitionerName" to "$practitionerFirstName $practitionerLastName",
    "name" to "$offenderFirstName $offenderLastName",
    "popDashboardUrl" to appConfig.dashboardUrl().toString(),
  )

  override val messageType: NotificationType
    get() = NotificationType.PractitionerInviteIssueGeneric

  companion object {
    fun fromCheckin(invite: OffenderCheckin): PractitionerInviteIssueGenericMessage = PractitionerInviteIssueGenericMessage(
      practitionerFirstName = invite.createdBy.firstName,
      practitionerLastName = invite.createdBy.lastName,
      offenderFirstName = invite.offender.firstName,
      offenderLastName = invite.offender.lastName,
    )
  }
}
