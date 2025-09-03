package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedIdVerificationResult
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
  val autoIdCheck: AutomatedIdVerificationResult?,
) : Message {
  override fun personalisationData(appConfig: AppConfig): Map<String, String> = mapOf(
    "practitionerName" to practitionerName,
    "name" to "$offenderFirstName $offenderLastName",
    "number" to totalFlags().toString(),
    "dashboardSubmissionUrl" to appConfig.checkinDashboardUrl(checkinUuid).toString(),
  )

  // we count a failed/missing automated ID check as a flag
  private fun totalFlags(): Int = numFlags + (if (autoIdCheck == AutomatedIdVerificationResult.NO_MATCH || autoIdCheck == null) 1 else 0)

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
        autoIdCheck = checkin.autoIdCheck,
      )
    }
  }
}
