package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class OffenderCheckinInviteMessage(
  val firstName: String,
  val lastName: String,
  val checkinDueDate: LocalDate,
  val checkinUuid: UUID,
) : Message {
  override fun personalisationData(appConfig: AppConfig): Map<String, String> = mapOf(
    "firstName" to firstName,
    "lastName" to lastName,
    "checkinDueDate" to DATE_FORMAT.format(checkinDueDate),
    "checkinURL" to appConfig.checkinSubmitUrl(checkinUuid).toString(),
  )

  override val messageType: NotificationType
    get() = NotificationType.OffenderCheckinInvite

  companion object {
    val LONDON_ZONE = ZoneId.of("Europe/London")
    val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun fromCheckin(checkin: OffenderCheckin): OffenderCheckinInviteMessage = OffenderCheckinInviteMessage(
      firstName = checkin.offender.firstName,
      lastName = checkin.offender.lastName,
      // checkinDueDate = ZonedDateTime.of(checkin.dueDate, LocalTime.of(0, 0, 0), LONDON_ZONE),
      // checkinDueDate = ZonedDateTime.ofInstant(checkin.dueDate,LONDON_ZONE).toLocalDate(),
      checkinDueDate = checkin.dueDate.withZoneSameLocal(LONDON_ZONE).toLocalDate(),
      checkinUuid = checkin.uuid,
    )
  }
}
