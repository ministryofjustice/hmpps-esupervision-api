package uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException

/**
 * @throws BadArgumentException if the check-in mode is invalid for the given interval
 */
fun validateScheduleSettings(mode: CheckinMode, interval: CheckinInterval?) {
  when {
    mode == CheckinMode.SCHEDULED && interval == null -> throw BadArgumentException("Check-in interval is required for scheduled check-ins.")
    mode == CheckinMode.AD_HOC && interval != null -> throw BadArgumentException("Check-in interval must be absent for ad-hoc check-ins.")
  }
}
