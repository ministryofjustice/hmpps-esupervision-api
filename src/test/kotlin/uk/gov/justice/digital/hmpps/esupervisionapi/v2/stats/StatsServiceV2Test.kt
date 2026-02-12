package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
  private val mapper = ObjectMapper()

  @Test
  fun `getStats returns stats when repository has data`() {

    val howEasyCounts: JsonNode =
      mapper.valueToTree(
        mapOf(
          "veryEasy" to 1L,
          "difficult" to 1L,
          "notAnswered" to 2L,
        ),
      )

    val howEasyPct: JsonNode =
      mapper.valueToTree(
        mapOf(
          "veryEasy" to 0.5,
          "difficult" to 0.5,
        ),
      )

    val gettingSupportCounts: JsonNode =
      mapper.valueToTree(
        mapOf(
          "yes" to 2L,
          "no" to 1L,
          "notAnswered" to 1L,
        ),
      )

    val gettingSupportPct: JsonNode =
      mapper.valueToTree(
        mapOf(
          "yes" to 0.6667,
          "no" to 0.3333,
        ),
      )

    val improvementsCounts: JsonNode =
      mapper.valueToTree(
        mapOf(
          "gettingHelp" to 1L,
          "checkInQuestions" to 2L,
          "notAnswered" to 1L,
        ),
      )

    val improvementsPct: JsonNode =
      mapper.valueToTree(
        mapOf(
          "gettingHelp" to 0.5,
          "checkInQuestions" to 1.0,
        ),
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
        pctActiveUsers = BigDecimal.valueOf(0.7),
        pctInactiveUsers = BigDecimal.valueOf(0.3),
        pctCompletedCheckins = BigDecimal.valueOf(0.9091),
        pctExpiredCheckins = BigDecimal.valueOf(0.0909),
        updatedAt = Instant.parse("2026-01-28T12:02:00.020175Z"),
        feedbackTotal = 4,
        howEasyCounts = howEasyCounts,
        howEasyPct = howEasyPct,
        gettingSupportCounts = gettingSupportCounts,
        gettingSupportPct = gettingSupportPct,
        improvementsCounts = improvementsCounts,
        improvementsPct = improvementsPct,
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
        updatedAt = Instant.parse("2026-01-28T12:02:00.020175Z"),
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
