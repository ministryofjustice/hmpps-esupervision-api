package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender

class OffenderCheckinsStoppedMessage(
  val firstName: String,
  val lastName: String,
) : Message {
  override fun personalisationData(appConfig: AppConfig): Map<String, String> = mapOf(
    "name" to "$firstName $lastName",
  )

  override val messageType: NotificationType
    get() = NotificationType.OffenderCheckinsStopped

  companion object {
    fun fromOffender(offender: Offender): OffenderCheckinsStoppedMessage = OffenderCheckinsStoppedMessage(
      firstName = offender.firstName,
      lastName = offender.lastName,
    )
  }
}
