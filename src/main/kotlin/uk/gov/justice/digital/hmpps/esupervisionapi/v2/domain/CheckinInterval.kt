package uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain

import java.time.Duration

enum class CheckinInterval(val duration: Duration) : Comparable<CheckinInterval> {
  WEEKLY(Duration.ofDays(7)),
  TWO_WEEKS(Duration.ofDays(14)),
  FOUR_WEEKS(Duration.ofDays(28)),
  EIGHT_WEEKS(Duration.ofDays(56)),
  ;

  companion object {
    fun fromDuration(duration: Duration): CheckinInterval = entries.firstOrNull { it.duration == duration }
      ?: throw IllegalArgumentException("No CheckinInterval for duration: $duration")
  }
}
