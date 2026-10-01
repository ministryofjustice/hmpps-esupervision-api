package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

enum class NotificationType {
  OffenderCheckinInvite,
  OffenderCheckinSubmitted,
  OffenderCheckinSubmittedAdHoc,
  OffenderCheckinsStopped,
  OffenderCheckinsStoppedAuto,
  OffenderCheckinsRestarted,
  OffenderCheckinsRestartedAdHoc,
  OffenderCheckinReminder,
  PractitionerCheckinSubmitted,
  PractitionerCheckinMissed,
  PractitionerInviteIssueGeneric,
  RegistrationConfirmation,
  RegistrationConfirmationAdHoc,
  PractitionerCustomQuestionsReminder,
  OffenderAdHocCheckinScheduled,
  OffenderCheckinCancelled,
  OffenderCheckinDateChanged,
  OffenderCheckinsFrequencyChanged,
}
