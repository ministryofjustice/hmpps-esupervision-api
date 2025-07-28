package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetup
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class RegistrationConfirmationMessage(
  val firstName: String,
  val lastName: String,
  val firstCheckinDate: LocalDate,
  val checkinInterval: CheckinInterval,
) : Message {
  override fun personalisationData(appConfig: AppConfig): Map<String, String> = mapOf(
    "name" to "$firstName $lastName",
    "date" to DATE_FORMATTER.format(firstCheckinDate),
    "frequency" to formatCheckinFrequency(checkinInterval)
  )

  override val messageType: NotificationType
    get() = NotificationType.RegistrationConfirmation

  companion object {
    val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun formatCheckinFrequency(checkinFrequency: CheckinInterval): String = when (checkinFrequency) {
      CheckinInterval.WEEKLY -> "week"
      CheckinInterval.TWO_WEEKS -> "two weeks"
      CheckinInterval.FOUR_WEEKS -> "four weeks"
    }

    fun fromSetup(offenderSetup: OffenderSetup): RegistrationConfirmationMessage = RegistrationConfirmationMessage(
      firstName = offenderSetup.offender.firstName,
      lastName = offenderSetup.offender.lastName,
      // NOTE: first checkin date should always be set for new registrations
      firstCheckinDate = offenderSetup.offender.firstCheckin!!,
      checkinInterval = CheckinInterval.fromDuration(offenderSetup.offender.checkinInterval),
    )
  }
}
