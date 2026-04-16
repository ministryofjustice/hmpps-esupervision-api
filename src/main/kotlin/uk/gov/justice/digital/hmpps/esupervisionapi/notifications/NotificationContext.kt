package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class NotificationContextType {
  SCHEDULED_JOB,
  SINGLE,
}

sealed class NotificationContext(
  val reference: String,
  /**
   * Answers why are we sending the notification
   */
  val type: NotificationContextType,
)

/**
 * To be used for bulk notifications (e.g., in a scheduled job, so that we can link
 * notifications to that job).
 */
data class BulkNotificationContext(val ref: String) : NotificationContext(ref, NotificationContextType.SCHEDULED_JOB)

/**
 * To be used for one-off notification.
 */
data class SingleNotificationContext(val ref: String) : NotificationContext(ref, NotificationContextType.SINGLE) {

  companion object {
    fun from(notificationId: UUID) = SingleNotificationContext("SNGL-$notificationId")

    fun from(message: Message, clock: Clock): NotificationContext = when (message.messageType) {
      // NOTE: the references starting with "O" are for notifs. sent to the offender, the ones with "P" to the practitioner.
      NotificationType.OffenderCheckinSubmitted -> {
        SingleNotificationContext("OSUB-${formatDate(clock.today())}")
      }
      NotificationType.OffenderCheckinsStopped -> {
        SingleNotificationContext("OSTP-${formatDate(clock.today())}")
      }
      NotificationType.OffenderCheckinsRestarted -> {
        SingleNotificationContext("ORES-${formatDate(clock.today())}")
      }
      NotificationType.RegistrationConfirmation -> {
        SingleNotificationContext("OREG-${formatDate(clock.today())}")
      }
      NotificationType.OffenderCheckinReminder -> {
        SingleNotificationContext("OREM-${formatDate(clock.today())}")
      }
      NotificationType.PractitionerCheckinSubmitted -> {
        SingleNotificationContext("PSUB-${formatDate(clock.today())}")
      }
      NotificationType.PractitionerCheckinMissed -> {
        SingleNotificationContext("PEXP-${formatDate(clock.today())}")
      }
      NotificationType.OffenderCheckinInvite -> {
        SingleNotificationContext(formatCheckinReference(clock.today()))
      }
      NotificationType.PractitionerInviteIssueGeneric -> {
        SingleNotificationContext("PING-${formatDate(clock.today())}")
      }
      NotificationType.PractitionerCustomQuestionsReminder -> {
        SingleNotificationContext("PCQR-${formatDate(clock.today())}")
      }
    }

    private fun formatDate(date: LocalDate): String? = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun forCheckin(now: LocalDate) = SingleNotificationContext(formatCheckinReference(now))

    fun forCheckin(clock: Clock) = forCheckin(clock.today())

    private fun formatCheckinReference(date: LocalDate) = "CHK-${formatDate(date)}"
  }
}

data class NotificationResultSummary(
  val notificationId: UUID,
  val context: NotificationContext,
  val timestamp: ZonedDateTime,
  val status: String?,
  val error: String?,
)

/**
 * NOTE: stored in as JSON in the DB
 */
data class NotificationResults(
  val results: List<NotificationResultSummary>,
)
