package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsServiceV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsWithPercentages
import java.math.BigDecimal
import java.time.Instant

class StatsServiceV2Test {

  private val repository: StatsSummaryRepository = mock()
  private val service = StatsServiceV2(repository)

  @Test
  fun `getStats returns stats when repository has data`() {
    val summary =
      StatsSummary(
        singleton = 1,
        totalSignedUp = 10,
        activeUsers = 7,
        inactiveUsers = 3,
        completedCheckins = 20,
        notCompletedOnTime = 2,
        avgHoursToComplete = BigDecimal.valueOf(5.5),
        avgCompletedCheckinsPerPerson = BigDecimal.valueOf(2.86),
        updatedAt = Instant.parse("2026-01-28T12:02:00.020175Z"),
        pctActiveUsers = BigDecimal.valueOf(0.7),
        pctInactiveUsers = BigDecimal.valueOf(0.3),
        pctCompletedCheckins = BigDecimal.valueOf(0.9091),
        pctExpiredCheckins = BigDecimal.valueOf(0.0909),
      )

    val expectedResult = StatsWithPercentages(
      totalSignedUp = 10,
      activeUsers = 7,
      inactiveUsers = 3,
      completedCheckins = 20,
      notCompletedOnTime = 2,
      avgHoursToComplete = 5.5,
      avgCompletedCheckinsPerPerson = 2.86,
      updatedAt = Instant.parse("2026-01-28T12:02:00.020175Z"),
      pctActiveUsers = 0.7,
      pctInactiveUsers = 0.3,
      pctCompletedCheckins = 0.9091,
      pctExpiredCheckins = 0.0909,
    )

    whenever(repository.findBySingleton(1)).thenReturn(summary)

    val result = service.getStats()

    assertEquals(expectedResult, result)
  }

  @Test
  fun `getStats throws exception when repository is empty`() {
    whenever(repository.findBySingleton(1)).thenReturn(null)

    val exception = assertThrows(IllegalStateException::class.java) { service.getStats() }

    assertEquals(
      "Stats summary not found – materialised view stats_summary_v1 is empty",
      exception.message,
    )
  }
}
