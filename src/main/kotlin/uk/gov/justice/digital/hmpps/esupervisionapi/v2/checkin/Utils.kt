package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

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
fun isCheckinDay(offender: OffenderV2, date: LocalDate): Boolean {
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
 */
fun nextCheckinDay(offender: OffenderV2, today: LocalDate): LocalDate {
  require(today >= offender.firstCheckin) { "Today ($today) is before first checkin ($offender.firstCheckin)" }

  val days = offender.firstCheckin.until(today, ChronoUnit.DAYS)
  val rem = days % offender.checkinInterval.toDays()
  return today.plusDays(offender.checkinInterval.toDays() - rem)
}
