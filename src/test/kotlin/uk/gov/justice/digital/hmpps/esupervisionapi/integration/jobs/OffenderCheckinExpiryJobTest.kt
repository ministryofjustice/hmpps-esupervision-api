package uk.gov.justice.digital.hmpps.esupervisionapi.integration.jobs

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.jobs.cutoffDate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class OffenderCheckinExpiryJobTest {

  @Test
  fun `cutoff date calculation`() {
    val clock = Clock.fixed(Instant.parse("2025-09-13T14:30:00Z"), ZoneId.systemDefault())
    val checkinWindow = Duration.ofHours(72)
    val actual = cutoffDate(clock, checkinWindow)

    Assertions.assertEquals(LocalDate.of(2025, 9, 10), actual)
  }
}
