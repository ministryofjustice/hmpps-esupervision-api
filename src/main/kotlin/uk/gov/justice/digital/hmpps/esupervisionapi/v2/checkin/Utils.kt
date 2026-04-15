package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinSchedule
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
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
fun activeEventNumber(offender: OffenderV2, details: ContactDetails): Long? {
  if (offender.currentEvent != null) {
    return offender.currentEvent
  }

  return details.events?.firstOrNull()?.number
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
fun nextCheckinDay(offender: CheckinSchedule, today: LocalDate): LocalDate {
  if (today < offender.firstCheckin) return offender.firstCheckin

  val days = offender.firstCheckin.until(today, ChronoUnit.DAYS)
  val rem = days % offender.checkinInterval.toDays()
  return today.plusDays(offender.checkinInterval.toDays() - rem)
}
