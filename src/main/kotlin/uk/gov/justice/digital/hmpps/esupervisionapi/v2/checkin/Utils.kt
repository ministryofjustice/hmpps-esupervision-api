package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2


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
