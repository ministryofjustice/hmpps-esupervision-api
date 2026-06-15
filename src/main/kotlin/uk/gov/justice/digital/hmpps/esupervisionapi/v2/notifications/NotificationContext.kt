package uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import java.time.Clock
import java.time.format.DateTimeFormatter

object NotificationContext {
  fun generateReference(notificationType: NotificationType, clock: Clock, env: String): String {
    val date = clock.today().format(DateTimeFormatter.ISO_LOCAL_DATE)

    return when (notificationType) {
      // Offender notifications
      NotificationType.OffenderCheckinSubmitted -> "OSUB-$date-$env"
      NotificationType.OffenderCheckinsStopped -> "OSTP-$date-$env"
      NotificationType.OffenderCheckinsRestarted -> "ORES-$date-$env"
      NotificationType.RegistrationConfirmation -> "OREG-$date-$env"
      NotificationType.OffenderCheckinInvite -> "OCHK-$date-$env"
      NotificationType.OffenderCheckinReminder -> "OREM-$date-$env"

      // Practitioner notifications
      NotificationType.PractitionerCheckinSubmitted -> "PSUB-$date-$env"
      NotificationType.PractitionerCheckinMissed -> "PEXP-$date-$env"
      NotificationType.PractitionerInviteIssueGeneric -> "PING-$date-$env"
      NotificationType.PractitionerCustomQuestionsReminder -> "PCQR-$date-$env"
    }
  }
}
