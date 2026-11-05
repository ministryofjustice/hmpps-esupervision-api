package uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents

import uk.gov.justice.digital.hmpps.esupervisionapi.utils.formatHumanReadableDateTime
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

/**
 * Contact note text for check-in NDelius events. Each starts with the "Check in status: ..." line,
 * as for submitted, reviewed and missed check-ins (see EventDetailService), followed by the detail.
 * NDelius adds each event's note to the check-in's contact.
 */
object CheckinDeliusEventNotes {
  fun scheduled(dueDate: LocalDate) = withStatus("Scheduled", "Check in due: ${dueDate.format(DATE_FORMATTER)}")

  fun rescheduled(newDueDate: LocalDate) = withStatus("Rescheduled", "Check in moved to: ${newDueDate.format(DATE_FORMATTER)}")

  /** @param finalCheckinDate the last day the check-in can be completed */
  fun completeBy(finalCheckinDate: LocalDate) = "Complete by: ${finalCheckinDate.format(DATE_FORMATTER)} at 11:59pm"

  fun cancelled(reason: String) = withStatus("Cancelled", "Reason: $reason")

  /** @param channel how the link was sent, for example "text message" */
  fun linkDelivered(channel: String, sentAt: Instant) = withStatus("Link sent", "Link sent by $channel: ${formatHumanReadableDateTime(sentAt)}")

  fun linkNotDelivered(reason: String) = withStatus("Link not sent", "Reason the link was not sent: $reason")

  private fun withStatus(status: String, detail: String) = "Check in status: $status\n\n$detail"
}
