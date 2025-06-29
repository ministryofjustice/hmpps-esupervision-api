package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import java.util.UUID

data class PractitionerCheckinSubmittedMessage(
  val practitionerFirstName: String,
  val offenderFirstName: String,
  val offenderLastName: String,
  val checkinUuid: UUID,
) : Message {
  override fun personalisationData(appConfig: AppConfig): Map<String, String> = mapOf(
    "practitionerFirstName" to practitionerFirstName,
    "offenderFirstName" to offenderFirstName,
    "offenderLastName" to offenderLastName,
    "checkinDashboardUrl" to appConfig.checkinDashboardUrl(checkinUuid).toString(),
  )

  override val templateName: String
    get() = "PRACTITIONER_CHECKIN_SUBMITTED"

  companion object {
    fun fromCheckin(checkin: OffenderCheckin) = PractitionerCheckinSubmittedMessage(
      practitionerFirstName = checkin.createdBy.firstName,
      offenderFirstName = checkin.createdBy.firstName,
      offenderLastName = checkin.createdBy.lastName,
      checkinUuid = checkin.uuid,
    )
  }
}
