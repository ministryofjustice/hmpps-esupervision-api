package uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import java.time.Clock
import java.time.format.DateTimeFormatter

object NotificationContextV2 {
  fun generateReference(notificationType: NotificationType, clock: Clock): String {
    val date = clock.today().format(DateTimeFormatter.ISO_LOCAL_DATE)
    return when (notificationType) {
      // Offender notifications
      NotificationType.OffenderCheckinSubmitted -> "OSUB-$date"
      NotificationType.OffenderCheckinsStopped -> "OSTP-$date"
      NotificationType.RegistrationConfirmation -> "OREG-$date"
      NotificationType.OffenderCheckinInvite -> "CHK-$date"

      // Practitioner notifications
      NotificationType.PractitionerCheckinSubmitted -> "PSUB-$date"
      NotificationType.PractitionerCheckinMissed -> "PEXP-$date"
      NotificationType.PractitionerInviteIssueGeneric -> "PING-$date"
    }
  }
}
