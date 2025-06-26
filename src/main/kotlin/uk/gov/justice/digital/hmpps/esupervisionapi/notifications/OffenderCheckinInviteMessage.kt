package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import java.util.UUID

data class OffenderCheckinInviteMessage(
  val firstName: String,
  val lastName: String,
  val checkinUuid: UUID,
) : Message {
  override fun personalisationData(appConfig: AppConfig): Map<String, String> = mapOf(
    "firstName" to firstName,
    "lastName" to lastName,
    "checkinURL" to appConfig.checkinSubmitUrl(checkinUuid).toString(),
  )

  override val templateName: String
    get() = "POP_CHECKIN_INVITE"

  companion object {
    fun fromCheckin(checkin: OffenderCheckin): OffenderCheckinInviteMessage = OffenderCheckinInviteMessage(
      firstName = checkin.offender.firstName,
      lastName = checkin.offender.lastName,
      checkinUuid = checkin.uuid,
    )
  }
}
