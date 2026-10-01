package uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import java.time.Clock
import java.time.LocalDate

val AD_HOC_UNSET_FIRST_CHECKIN = LocalDate.of(1970, 1, 1)

/**
 * @throws BadArgumentException if the check-in mode is invalid for the given interval
 */
fun validateScheduleSettings(mode: CheckinMode, interval: CheckinInterval?) {
  when {
    mode == CheckinMode.SCHEDULED && interval == null -> throw BadArgumentException("Check-in interval is required for scheduled check-ins.")
    mode == CheckinMode.AD_HOC && interval != null -> throw BadArgumentException("Check-in interval must be absent for ad-hoc check-ins.")
  }
}

fun resolveFirstCheckinForPersistence(mode: CheckinMode, firstCheckin: LocalDate?, clock: Clock): LocalDate {
  return when {
    mode == CheckinMode.AD_HOC && firstCheckin == null -> AD_HOC_UNSET_FIRST_CHECKIN
    mode == CheckinMode.AD_HOC && firstCheckin != null -> firstCheckin
    mode == CheckinMode.SCHEDULED && firstCheckin == null -> throw BadArgumentException("First check-in date is required for scheduled check-ins.")
    else -> firstCheckin ?: throw BadArgumentException("First check-in date is required.")
  }
}

fun isUnsetAdHocFirstCheckin(mode: CheckinMode, firstCheckin: LocalDate): Boolean =
  mode == CheckinMode.AD_HOC && firstCheckin == AD_HOC_UNSET_FIRST_CHECKIN
