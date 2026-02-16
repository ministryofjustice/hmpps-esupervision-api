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
    val howEasyCounts: Map<String, Long> =
      mapOf(
        "veryEasy" to 1L,
        "difficult" to 1L,
        "notAnswered" to 2L,
      )

    val howEasyPct: Map<String, BigDecimal> =
      mapOf(
        "veryEasy" to BigDecimal("0.5"),
        "difficult" to BigDecimal("0.5"),
      )

    val gettingSupportCounts: Map<String, Long> =
      mapOf(
        "yes" to 2L,
        "no" to 1L,
        "notAnswered" to 1L,
      )

    val gettingSupportPct: Map<String, BigDecimal> =
      mapOf(
        "yes" to BigDecimal("0.6667"),
        "no" to BigDecimal("0.3333"),
      )

    val improvementsCounts: Map<String, Long> =
      mapOf(
        "gettingHelp" to 1L,
        "checkInQuestions" to 2L,
        "notAnswered" to 1L,
      )

    val improvementsPct: Map<String, BigDecimal> =
      mapOf(
        "gettingHelp" to BigDecimal("0.5"),
        "checkInQuestions" to BigDecimal("1.0"),
      )

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

        pctActiveUsers = BigDecimal("0.7"),
        pctInactiveUsers = BigDecimal("0.3"),
        pctCompletedCheckins = BigDecimal("0.9091"),
        pctExpiredCheckins = BigDecimal("0.0909"),

        feedbackTotal = 4,
        howEasyCounts = howEasyCounts,
        howEasyPct = howEasyPct,
        gettingSupportCounts = gettingSupportCounts,
        gettingSupportPct = gettingSupportPct,
        improvementsCounts = improvementsCounts,
        improvementsPct = improvementsPct,

        updatedAt = Instant.parse("2026-01-28T12:02:00.020175Z"),
      )

    val expectedResult =
      StatsWithPercentages(
        totalSignedUp = 10,
        activeUsers = 7,
        inactiveUsers = 3,
        completedCheckins = 20,
        notCompletedOnTime = 2,
        avgHoursToComplete = 5.5,
        avgCompletedCheckinsPerPerson = 2.86,

        pctActiveUsers = 0.7,
        pctInactiveUsers = 0.3,
        pctCompletedCheckins = 0.9091,
        pctExpiredCheckins = 0.0909,

        feedbackTotal = 4,
        howEasyCounts = howEasyCounts,
        howEasyPct = howEasyPct,
        gettingSupportCounts = gettingSupportCounts,
        gettingSupportPct = gettingSupportPct,
        improvementsCounts = improvementsCounts,
        improvementsPct = improvementsPct,

        updatedAt = Instant.parse("2026-01-28T12:02:00.020175Z"),
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
