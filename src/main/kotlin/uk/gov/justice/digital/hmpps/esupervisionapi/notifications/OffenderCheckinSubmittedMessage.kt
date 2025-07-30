package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin

data class OffenderCheckinSubmittedMessage(
  val firstName: String,
  val lastName: String,
) : Message {
  override fun personalisationData(appConfig: AppConfig): Map<String, String> = mapOf(
    "name" to "$firstName $lastName",
  )

  override val messageType: NotificationType
    get() = NotificationType.OffenderCheckinSubmitted

  companion object {
    fun fromCheckin(checkin: OffenderCheckin): OffenderCheckinSubmittedMessage = OffenderCheckinSubmittedMessage(
      firstName = checkin.offender.firstName,
      lastName = checkin.offender.lastName,
    )
  }
}
