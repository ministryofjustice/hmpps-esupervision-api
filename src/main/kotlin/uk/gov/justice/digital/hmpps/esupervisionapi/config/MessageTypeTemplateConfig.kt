package uk.gov.justice.digital.hmpps.esupervisionapi.config

import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType

class MessageTypeTemplateConfig(
  private val popCheckinInvite: String,
  private val practitionerCheckinSubmitted: String,
  private val practitionerCheckinMissed: String,
  private val practitionerInviteIssueGeneric: String,
  private val practitionerCustomQuestionReminder: String,
  private val popRegistrationConfirmation: String,
  private val popRegistrationConfirmationAdHoc: String,
  private val popSubmissionConfirmation: String,
  private val popSubmissionConfirmationAdHoc: String,
  private val popCheckinsStopped: String,
  private val popCheckinsStoppedAuto: String,
  private val popCheckinReminder: String,
  private val popCheckinsRestarted: String,
  private val popCheckinsRestartedAdHoc: String,
  private val popAdHocCheckinScheduled: String,
  private val popCheckinCancelled: String,
  private val popCheckinDateChanged: String,
  private val popCheckinsFrequencyChanged: String,
) {
  fun getTemplate(messageType: NotificationType): String = when (messageType) {
    NotificationType.OffenderCheckinInvite -> this.popCheckinInvite
    NotificationType.OffenderCheckinSubmitted -> this.popSubmissionConfirmation
    NotificationType.OffenderCheckinSubmittedAdHoc -> this.popSubmissionConfirmationAdHoc
    NotificationType.OffenderCheckinsStopped -> this.popCheckinsStopped
    NotificationType.OffenderCheckinsStoppedAuto -> this.popCheckinsStoppedAuto
    NotificationType.PractitionerCheckinSubmitted -> this.practitionerCheckinSubmitted
    NotificationType.PractitionerCheckinMissed -> this.practitionerCheckinMissed
    NotificationType.PractitionerInviteIssueGeneric -> this.practitionerInviteIssueGeneric
    NotificationType.PractitionerCustomQuestionsReminder -> this.practitionerCustomQuestionReminder
    NotificationType.RegistrationConfirmation -> this.popRegistrationConfirmation
    NotificationType.RegistrationConfirmationAdHoc -> this.popRegistrationConfirmationAdHoc
    NotificationType.OffenderCheckinReminder -> this.popCheckinReminder
    NotificationType.OffenderCheckinsRestarted -> this.popCheckinsRestarted
    NotificationType.OffenderCheckinsRestartedAdHoc -> this.popCheckinsRestartedAdHoc
    NotificationType.OffenderAdHocCheckinScheduled -> this.popAdHocCheckinScheduled
    NotificationType.OffenderCheckinCancelled -> this.popCheckinCancelled
    NotificationType.OffenderCheckinDateChanged -> this.popCheckinDateChanged
    NotificationType.OffenderCheckinsFrequencyChanged -> this.popCheckinsFrequencyChanged
  }
}
