package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

enum class NotificationType {
  OffenderCheckinInvite,
  OffenderCheckinSubmitted,
  OffenderCheckinsStopped,
  OffenderCheckinsRestarted,
  OffenderCheckinReminder,
  PractitionerCheckinSubmitted,
  PractitionerCheckinMissed,
  PractitionerInviteIssueGeneric,
  RegistrationConfirmation,
  PractitionerCustomQuestionsReminder,
}
