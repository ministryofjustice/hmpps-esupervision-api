package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import java.util.UUID

data class PractitionerCheckinMissedMessage(
  val practitionerName: String,
  val offenderFirstName: String,
  val offenderLastName: String,
  val checkin: UUID,
) : Message {
  override fun personalisationData(appConfig: AppConfig): Map<String, String> = mapOf(
    "practitionerName" to practitionerName,
    "name" to "$offenderFirstName $offenderLastName",
    "popDashboardUrl" to appConfig.checkinDashboardUrl(checkin).toString(),
  )

  override val messageType: NotificationType
    get() = NotificationType.PractitionerCheckinMissed

  companion object {
    fun fromCheckin(checkin: OffenderCheckin, practitioner: Practitioner): PractitionerCheckinMissedMessage = PractitionerCheckinMissedMessage(
      practitionerName = practitioner.name,
      offenderFirstName = checkin.offender.firstName,
      offenderLastName = checkin.offender.lastName,
      checkin = checkin.uuid,
    )
  }
}
