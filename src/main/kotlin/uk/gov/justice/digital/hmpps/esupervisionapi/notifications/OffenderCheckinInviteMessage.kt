package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.UUID

data class OffenderCheckinInviteMessage(
  val firstName: String,
  val lastName: String,
  val checkinDueDate: LocalDate,
  /**
   * Last day when the checkin can be submitted by an offender
   */
  val finalCheckinDate: LocalDate,
  val checkinUuid: UUID,
) : Message {
  override fun personalisationData(appConfig: AppConfig): Map<String, String> = mapOf(
    "firstName" to firstName,
    "lastName" to lastName,
    "date" to DATE_FORMAT.format(checkinDueDate),
    "url" to appConfig.checkinSubmitUrl(checkinUuid).toString(),
  )

  override val messageType: NotificationType
    get() = NotificationType.OffenderCheckinInvite

  companion object {
    val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun fromCheckin(checkin: OffenderCheckin, checkinWindow: Period): OffenderCheckinInviteMessage = OffenderCheckinInviteMessage(
      firstName = checkin.offender.firstName,
      lastName = checkin.offender.lastName,
      checkinDueDate = checkin.dueDate,
      finalCheckinDate = checkin.dueDate.plus(checkinWindow),
      checkinUuid = checkin.uuid,
    )
  }
}
