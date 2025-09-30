package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import java.time.Clock
import java.time.LocalDate

/**
 * @return today according to the clock's zone
 */
fun Clock.today(): LocalDate {
  val now = this.instant()
  return now.atZone(this.zone).toLocalDate()
}
