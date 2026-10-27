package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ActiveEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinSchedule
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Returns the offender's saved event number when it appears in the active events returned by NDelius.
 * Without a saved selection, returns the sole sentenced event or the sentence with the latest expected
 * end date when all sentenced events are dated. Returns null when multiple sentences cannot be ranked,
 * and falls back to the first returned event when no sentenced events are present.
 *
 * @see uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
 */
fun activeEventNumber(offender: ActiveEvent, details: ContactDetails): Long? = if (offender.currentEvent != null) {
  details.events.firstOrNull { it.number == offender.currentEvent }?.number
} else {
  val sentenceEvents = details.events.filter { it.sentence != null }
  when {
    sentenceEvents.size > 1 && sentenceEvents.any { it.sentence?.expectedEndDate == null } -> null
    sentenceEvents.size == 1 -> sentenceEvents.single().number
    sentenceEvents.size > 1 -> sentenceEvents.maxWithOrNull(
      compareBy<Event> { requireNotNull(it.sentence?.expectedEndDate) }.thenBy { it.number },
    )?.number
    else -> details.events.firstOrNull()?.number
  }
}

/**
 * Reasons a verified offender should no longer receive online check-ins.
 * The [auditNote] is recorded against the automated deactivation audit event.
 */
enum class CheckinIneligibilityReason(
  val description: String,
  /** The audit event type recorded when a scheduled job stops check-ins for this reason. */
  val auditEventType: OffenderAuditEventType,
) {
  CONTACT_SUSPENDED(
    "contact is suspended (in reset) in NDelius",
    OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED,
  ),
  NO_ACTIVE_EVENTS(
    "there are no active probation events in NDelius",
    OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS,
  ),
  SELECTED_EVENT_NO_LONGER_ACTIVE(
    "the selected probation event is no longer active in NDelius",
    OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_SELECTED_EVENT_NO_LONGER_ACTIVE,
  ),
  ;

  /** Human-readable reason text recorded in the audit event's notes. */
  val auditNote: String get() = "Automatically deactivated: $description"
}

/**
 * Determines whether an offender should be stopped from receiving online check-ins, based on the
 * latest Delius contact details. Returns the reason, or null if the offender is still eligible.
 *
 * Stops check-ins when the person is in reset, has no active probation events, or their selected event
 * is no longer active in NDelius.
 *
 * Note on no-active-events: we only treat this as grounds for stopping when NDelius returns an
 * explicitly empty events list when no selection is saved. If a selection is saved, NDelius must
 * return that event; another active event does not keep the existing setup eligible.
 *
 * @see activeEventNumber
 */
fun checkinIneligibilityReason(offender: ActiveEvent, details: ContactDetails): CheckinIneligibilityReason? = when {
  offender.currentEvent == null && details.events.isEmpty() -> CheckinIneligibilityReason.NO_ACTIVE_EVENTS
  details.contactSuspended -> CheckinIneligibilityReason.CONTACT_SUSPENDED
  offender.currentEvent != null && details.events.none { it.number == offender.currentEvent } ->
    CheckinIneligibilityReason.SELECTED_EVENT_NO_LONGER_ACTIVE
  else -> null
}

/**
 * Check if the date is a scheduled check-in day for the given offender.
 *
 * Note: the check relies on the constraint that an offender with a null check-in interval
 * is on ad-hoc check-ins (e.g. offender.firstCheckin is their closest check-in date).
 */
fun isCheckinDay(offender: CheckinSchedule, date: LocalDate): Boolean {
  val intervalDuration = offender.checkinInterval
  if (intervalDuration == null) {
    return date == offender.firstCheckin
  }
  val firstCheckin = requireNotNull(offender.firstCheckin) { "First check-in date is required for scheduled check-ins" }
  if (intervalDuration.toDays() > 0) {
    if (date < firstCheckin) {
      return false
    }

    val delta = firstCheckin.until(date, ChronoUnit.DAYS)
    val interval = intervalDuration.toDays()
    return delta % interval == 0L
  }
  return false
}

/**
 * Returns the next scheduled check-in day (excluding today - assuming check-in already created).
 *
 * Note: if "next check-in day" is today, it can be confusing because it depends on what time the check-in is created.
 * If we ask before it happens, *today* is the "next check-in day", if we ask *after* it happens, it's the *next* day.
 * But I'd like this function to not require an extra DB call (to see if the check-in already exists). So we will
 * assume that today is excluded from the possible "next check-in day."
 */
fun nextCheckinDay(schedule: CheckinSchedule, today: LocalDate): LocalDate {
  val intervalDuration = schedule.checkinInterval
  require(intervalDuration != null) { "Check-in interval is required for scheduled check-ins" }
  val firstCheckin = requireNotNull(schedule.firstCheckin) { "First check-in date is required for scheduled check-ins" }
  if (today < firstCheckin) return firstCheckin

  val days = firstCheckin.until(today, ChronoUnit.DAYS)
  val rem = days % intervalDuration.toDays()
  return today.plusDays(intervalDuration.toDays() - rem)
}

enum class CheckinScheduleLowerBound {
  INCLUDE_TODAY,
  EXCLUDE_TODAY,
}

fun nextCheckinDay(schedule: CheckinSchedule, today: LocalDate, bounds: CheckinScheduleLowerBound): LocalDate = when (bounds) {
  CheckinScheduleLowerBound.EXCLUDE_TODAY -> nextCheckinDay(schedule, today)
  CheckinScheduleLowerBound.INCLUDE_TODAY -> nextCheckinDay(schedule, today.minusDays(1))
}
