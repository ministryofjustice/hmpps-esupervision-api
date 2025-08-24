package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.NullResourceLocator
import java.util.UUID

data class PractitionerCheckinSubmittedMessage(
  val practitionerName: String,
  val offenderFirstName: String,
  val offenderLastName: String,
  val numFlags: Int,
  val checkinUuid: UUID,
) : Message {
  override fun personalisationData(appConfig: AppConfig): Map<String, String> = mapOf(
    "practitionerName" to practitionerName,
    "name" to "$offenderFirstName $offenderLastName",
    "number" to numFlags.toString(),
    "dashboardSubmissionUrl" to appConfig.checkinDashboardUrl(checkinUuid).toString(),
  )

  override val messageType: NotificationType
    get() = NotificationType.PractitionerCheckinSubmitted

  companion object {
    fun fromCheckin(checkin: OffenderCheckin, practitioner: Practitioner): PractitionerCheckinSubmittedMessage {
      val flags = checkin.dto(NullResourceLocator()).flaggedResponses

      return PractitionerCheckinSubmittedMessage(
        practitionerName = practitioner.name,
        offenderFirstName = checkin.offender.firstName,
        offenderLastName = checkin.offender.lastName,
        numFlags = flags.size,
        checkinUuid = checkin.uuid,
      )
    }
  }
}
