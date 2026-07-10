package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ActiveEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinSchedule
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Finds the active event number for the offender, when available.
 *
 * Note: We check the offender and contact details, because we don't collect and set the event number
 * during offender onboarding (meaning, they'll be mostly nulls). In contact details we find the first
 * event not associated with a completed sentence. This will enable us to stop sending messages
 * to Delius when someone does not have an ongoing event.
 *
 * @see uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
 */
fun activeEventNumber(offender: ActiveEvent, details: ContactDetails): Long? {
  if (offender.currentEvent != null) {
    return offender.currentEvent
  }

  return details.events?.firstOrNull()?.number
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
  ;

  /** Human-readable reason text recorded in the audit event's notes. */
  val auditNote: String get() = "Automatically deactivated: $description"
}

/**
 * Determines whether an offender should be stopped from receiving online check-ins, based on the
 * latest Delius contact details. Returns the reason, or null if the offender is still eligible.
 *
 * Stops check-ins when the person is in reset (contact suspended) or has no active probation events.
 *
 * Note on no-active-events: we only treat this as grounds for stopping when NDelius returns an
 * explicitly empty events list. An absent/null events list is treated as indeterminate (e.g. a
 * partial or degraded response) and does NOT stop check-ins, so a transient data gap can't wrongly
 * off-board an active person. A cached [Offender.currentEvent] always counts as an active event.
 *
 * @see activeEventNumber
 */
fun checkinIneligibilityReason(offender: ActiveEvent, details: ContactDetails): CheckinIneligibilityReason? = when {
  offender.currentEvent == null && details.events.isEmpty() -> CheckinIneligibilityReason.NO_ACTIVE_EVENTS
  details.contactSuspended -> CheckinIneligibilityReason.CONTACT_SUSPENDED
  else -> null
}

/**
 * Check if the date is a checkin day for the given offender
 */
fun isCheckinDay(offender: CheckinSchedule, date: LocalDate): Boolean {
  val firstCheckin = offender.firstCheckin
  if (offender.checkinInterval.toDays() > 0) {
    if (date < firstCheckin) {
      return false
    }

    val delta = firstCheckin.until(date, ChronoUnit.DAYS)
    val interval = offender.checkinInterval.toDays()
    return delta % interval == 0L
  }
  return false
}

/**
 * Returns the next checkin day (excluding today - assuming checkin already created. See [isCheckinDay]).
 *
 * Note: if "next checki day" is today, it can be confusing because it depends on what time the checkin is created.
 * If we ask before it happens, *today* is the "next checkin day", if we ask *after* it happens, it's the *next* day.
 * But I'd like this function to not require an extra DB call (to see if the checkin already exists). So we will
 * assume that today is excluded from the possible "next checkin day."
 */
fun nextCheckinDay(schedule: CheckinSchedule, today: LocalDate): LocalDate {
  if (today < schedule.firstCheckin) return schedule.firstCheckin

  val days = schedule.firstCheckin.until(today, ChronoUnit.DAYS)
  val rem = days % schedule.checkinInterval.toDays()
  return today.plusDays(schedule.checkinInterval.toDays() - rem)
}

enum class CheckinScheduleLowerBound {
  INCLUDE_TODAY,
  EXCLUDE_TODAY,
}

fun nextCheckinDay(schedule: CheckinSchedule, today: LocalDate, bounds: CheckinScheduleLowerBound): LocalDate = when (bounds) {
  CheckinScheduleLowerBound.EXCLUDE_TODAY -> nextCheckinDay(schedule, today)
  CheckinScheduleLowerBound.INCLUDE_TODAY -> nextCheckinDay(schedule, today.minusDays(1))
}
