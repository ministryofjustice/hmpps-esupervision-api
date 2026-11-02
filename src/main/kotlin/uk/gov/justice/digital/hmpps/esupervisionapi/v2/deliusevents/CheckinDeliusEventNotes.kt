package uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents

import uk.gov.justice.digital.hmpps.esupervisionapi.utils.formatHumanReadableDateTime
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

/**
 * Contact note text for check-in NDelius events. NDelius adds its own "Check in status: ..."
 * line for each event, so these only carry the detail.
 */
object CheckinDeliusEventNotes {
  fun scheduled(dueDate: LocalDate) = "Check in due: ${dueDate.format(DATE_FORMATTER)}"

  fun rescheduled(newDueDate: LocalDate) = "Check in moved to: ${newDueDate.format(DATE_FORMATTER)}"

  fun cancelled(reason: String) = "Reason: $reason"

  /** @param channel how the link was sent, for example "text message" */
  fun linkDelivered(channel: String, sentAt: Instant) = "Link sent by $channel: ${formatHumanReadableDateTime(sentAt)}"

  fun linkNotDelivered(reason: String) = "Reason the link was not sent: $reason"
}
