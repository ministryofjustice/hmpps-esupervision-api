package uk.gov.justice.digital.hmpps.esupervisionapi.config

import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType

class MessageTypeTemplateConfig(
  private val popCheckinInvite: String,
  private val practitionerCheckinSubmitted: String,
  private val popRegistrationConfirmation: String,
) {
  fun getTemplate(messageType: NotificationType): String = when (messageType) {
    NotificationType.OffenderCheckinInvite -> this.popCheckinInvite
    NotificationType.PractitionerCheckinSubmitted -> this.practitionerCheckinSubmitted
    NotificationType.RegistrationConfirmation -> this.popRegistrationConfirmation
  }
}
