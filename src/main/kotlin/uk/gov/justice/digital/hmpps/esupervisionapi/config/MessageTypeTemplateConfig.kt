package uk.gov.justice.digital.hmpps.esupervisionapi.config

import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType

class MessageTypeTemplateConfig(
  private val popCheckinInvite: String,
  private val practitionerCheckinSubmitted: String,
  private val practitionerCheckinMissed: String,
  private val popRegistrationConfirmation: String,
  private val popSubmissionConfirmation: String,
  private val popCheckinsStopped: String,
) {
  fun getTemplate(messageType: NotificationType): String = when (messageType) {
    NotificationType.OffenderCheckinInvite -> this.popCheckinInvite
    NotificationType.OffenderCheckinSubmitted -> this.popSubmissionConfirmation
    NotificationType.OffenderCheckinsStopped -> this.popCheckinsStopped
    NotificationType.PractitionerCheckinSubmitted -> this.practitionerCheckinSubmitted
    NotificationType.PractitionerCheckinMissed -> this.practitionerCheckinMissed
    NotificationType.RegistrationConfirmation -> this.popRegistrationConfirmation
  }
}
