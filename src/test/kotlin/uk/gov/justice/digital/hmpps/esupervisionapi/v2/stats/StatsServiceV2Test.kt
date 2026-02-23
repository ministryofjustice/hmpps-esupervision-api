package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsDashboardDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsPduDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsServiceV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsTotalsDto
import java.math.BigDecimal
import java.time.Instant

class StatsServiceV2Test {

  private val repository: StatsSummaryRepository = mock()
  private val service = StatsServiceV2(repository)

  @Test
  fun `getStats returns stats when repository has ALL row and PDU rows`() {
    val howEasyCounts =
      mapOf(
        "veryEasy" to 1L,
        "difficult" to 1L,
        "notAnswered" to 2L,
      )

    val howEasyPct =
      mapOf(
        "veryEasy" to BigDecimal("0.5"),
        "difficult" to BigDecimal("0.5"),
      )

    val gettingSupportCounts =
      mapOf(
        "yes" to 2L,
        "no" to 1L,
        "notAnswered" to 1L,
      )

    val gettingSupportPct =
      mapOf(
        "yes" to BigDecimal("0.6667"),
        "no" to BigDecimal("0.3333"),
      )

    val improvementsCounts =
      mapOf(
        "gettingHelp" to 1L,
        "checkInQuestions" to 2L,
        "notAnswered" to 1L,
      )

    val improvementsPct =
      mapOf(
        "gettingHelp" to BigDecimal("0.5"),
        "checkInQuestions" to BigDecimal("1.0"),
      )

    val updatedAt = Instant.parse("2026-01-28T12:02:00.020175Z")

    val overall =
      StatsSummary(
        id = StatsSummaryId(rowType = "ALL", pduCode = null),
        pduDescription = null,

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

        pctSignedUpOfTotal = BigDecimal("1.0"),
        updatedAt = updatedAt,
      )

    val pdu1 =
      StatsSummary(
        id = StatsSummaryId(rowType = "PDU", pduCode = "WPTNWS"),
        pduDescription = "North Wales",

        totalSignedUp = 4,
        activeUsers = 3,
        inactiveUsers = 1,
        completedCheckins = 8,
        notCompletedOnTime = 1,
        avgHoursToComplete = BigDecimal("4.25"),
        avgCompletedCheckinsPerPerson = BigDecimal("2.00"),

        pctActiveUsers = BigDecimal("0.75"),
        pctInactiveUsers = BigDecimal("0.25"),
        pctCompletedCheckins = BigDecimal("0.8889"),
        pctExpiredCheckins = BigDecimal("0.1111"),

        // feedback fields exist but are irrelevant for PDU rows; MV sets defaults
        feedbackTotal = 0,
        howEasyCounts = emptyMap(),
        howEasyPct = emptyMap(),
        gettingSupportCounts = emptyMap(),
        gettingSupportPct = emptyMap(),
        improvementsCounts = emptyMap(),
        improvementsPct = emptyMap(),

        pctSignedUpOfTotal = BigDecimal("0.4"),
        updatedAt = updatedAt,
      )

    val pdu2 =
      StatsSummary(
        id = StatsSummaryId(rowType = "PDU", pduCode = "N07ALL"),
        pduDescription = "All London",

        totalSignedUp = 6,
        activeUsers = 4,
        inactiveUsers = 2,
        completedCheckins = 12,
        notCompletedOnTime = 1,
        avgHoursToComplete = BigDecimal("6.00"),
        avgCompletedCheckinsPerPerson = BigDecimal("3.00"),

        pctActiveUsers = BigDecimal("0.6667"),
        pctInactiveUsers = BigDecimal("0.3333"),
        pctCompletedCheckins = BigDecimal("0.9231"),
        pctExpiredCheckins = BigDecimal("0.0769"),

        feedbackTotal = 0,
        howEasyCounts = emptyMap(),
        howEasyPct = emptyMap(),
        gettingSupportCounts = emptyMap(),
        gettingSupportPct = emptyMap(),
        improvementsCounts = emptyMap(),
        improvementsPct = emptyMap(),

        pctSignedUpOfTotal = BigDecimal("0.6"),
        updatedAt = updatedAt,
      )

    whenever(repository.findOverallRow()).thenReturn(overall)
    whenever(repository.findPduRows()).thenReturn(listOf(pdu1, pdu2))

    val result = service.getStats()

    val expected =
      StatsDashboardDto(
        total =
          StatsTotalsDto(
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
            pctSignedUpOfTotal = 1.0,
            updatedAt = updatedAt,
          ),
        pdus =
          listOf(
            StatsPduDto(
              pduCode = "WPTNWS",
              pduDescription = "North Wales",
              totalSignedUp = 4,
              activeUsers = 3,
              inactiveUsers = 1,
              completedCheckins = 8,
              notCompletedOnTime = 1,
              avgHoursToComplete = 4.25,
              avgCompletedCheckinsPerPerson = 2.0,
              pctActiveUsers = 0.75,
              pctInactiveUsers = 0.25,
              pctCompletedCheckins = 0.8889,
              pctExpiredCheckins = 0.1111,
              pctSignedUpOfTotal = 0.4,
              updatedAt = updatedAt,
            ),
            StatsPduDto(
              pduCode = "N07ALL",
              pduDescription = "All London",
              totalSignedUp = 6,
              activeUsers = 4,
              inactiveUsers = 2,
              completedCheckins = 12,
              notCompletedOnTime = 1,
              avgHoursToComplete = 6.0,
              avgCompletedCheckinsPerPerson = 3.0,
              pctActiveUsers = 0.6667,
              pctInactiveUsers = 0.3333,
              pctCompletedCheckins = 0.9231,
              pctExpiredCheckins = 0.0769,
              pctSignedUpOfTotal = 0.6,
              updatedAt = updatedAt,
            ),
          ),
      )

    assertEquals(expected, result)
  }

  @Test
  fun `getStats throws exception when ALL row missing`() {
    whenever(repository.findOverallRow()).thenReturn(null)

    val exception = assertThrows(IllegalStateException::class.java) { service.getStats() }

    assertEquals(
      "Stats summary not found – materialised view stats_summary_v1 has no ALL row",
      exception.message,
    )
  }
}