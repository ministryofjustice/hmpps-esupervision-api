package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetup

data class RegistrationConfirmationMessage(
  val firstName: String,
  val lastName: String,
) : Message {
  override fun personalisationData(appConfig: AppConfig): Map<String, String> = mapOf(
    "firstName" to firstName,
    "lastName" to lastName,
  )

  override val messageType: NotificationType
    get() = NotificationType.RegistrationConfirmation

  companion object {
    fun fromSetup(offenderSetup: OffenderSetup): RegistrationConfirmationMessage = RegistrationConfirmationMessage(
      firstName = offenderSetup.offender.firstName,
      lastName = offenderSetup.offender.lastName,
    )
  }
}
