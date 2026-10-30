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

  /**
   * Checkin stopped via automated process.
   */
  PractitionerCheckinsStoppedAuto,
  RegistrationConfirmation,
  RegistrationConfirmationAdHoc,
  PractitionerCustomQuestionsReminder,
  OffenderAdHocCheckinScheduled,
  OffenderCheckinCancelled,
  OffenderCheckinDateChanged,
  OffenderCheckinsFrequencyChanged,
}
