package uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class StatsResourceV2Test {

  private val service: StatsServiceV2 = mock()
  private val resource = StatsResourceV2(service)

  @Test
  fun `getStats - month param calls getStatsForMonth and maps response`() {
    val howEasyCounts =
      mapOf(
        "veryEasy" to 1L,
        "difficult" to 1L,
        "notAnswered" to 2L,
      )

    val howEasyPct =
      mapOf(
        "veryEasy" to BigDecimal("0.2500"),
        "difficult" to BigDecimal("0.2500"),
      )

    val gettingSupportCounts =
      mapOf(
        "no" to 1L,
        "yes" to 2L,
        "notAnswered" to 1L,
      )

    val gettingSupportPct =
      mapOf(
        "no" to BigDecimal("0.3333"),
        "yes" to BigDecimal("0.6667"),
      )

    val improvementsCounts =
      mapOf(
        "gettingHelp" to 1L,
        "checkInQuestions" to 2L,
        "notAnswered" to 1L,
      )

    val improvementsPct =
      mapOf(
        "gettingHelp" to BigDecimal("0.5000"),
        "checkInQuestions" to BigDecimal("1.0000"),
      )

    val totalsUpdatedAt = Instant.parse("2026-01-28T12:02:00.020175Z")
    val providerUpdatedAt = Instant.parse("2026-01-28T12:02:00.000000Z")

    val totals =
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
        pctCompletedCheckins = 1.0,
        pctExpiredCheckins = 0.1,
        feedbackTotal = 10,
        howEasyCounts = howEasyCounts,
        howEasyPct = howEasyPct,
        gettingSupportCounts = gettingSupportCounts,
        gettingSupportPct = gettingSupportPct,
        improvementsCounts = improvementsCounts,
        improvementsPct = improvementsPct,
        pctSignedUpOfTotal = 1.0,
        updatedAt = totalsUpdatedAt,
      )

    val provider1 =
      StatsProviderDto(
        providerCode = "WPTNWS",
        providerDescription = "North Wales",
        totalSignedUp = 4,
        activeUsers = 3,
        inactiveUsers = 1,
        completedCheckins = 8,
        notCompletedOnTime = 1,
        avgHoursToComplete = 4.25,
        avgCompletedCheckinsPerPerson = 2.00,
        pctActiveUsers = 0.75,
        pctInactiveUsers = 0.25,
        pctCompletedCheckins = 0.8889,
        pctExpiredCheckins = 0.1111,
        pctSignedUpOfTotal = 0.4,
        updatedAt = providerUpdatedAt,
      )

    val provider2 =
      StatsProviderDto(
        providerCode = "N07ALL",
        providerDescription = "All London",
        totalSignedUp = 6,
        activeUsers = 4,
        inactiveUsers = 2,
        completedCheckins = 12,
        notCompletedOnTime = 1,
        avgHoursToComplete = 6.00,
        avgCompletedCheckinsPerPerson = 3.00,
        pctActiveUsers = 0.6667,
        pctInactiveUsers = 0.3333,
        pctCompletedCheckins = 0.9231,
        pctExpiredCheckins = 0.0769,
        pctSignedUpOfTotal = 0.6,
        updatedAt = providerUpdatedAt,
      )

    whenever(service.getStatsForMonths(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01")))
      .thenReturn(
        StatsDashboardDto(
          total = totals,
          providers = listOf(provider1, provider2),
        ),
      )

    val response = resource.getStats(fromMonth = "2026-01", toMonth = "2026-01")

    assertEquals(HttpStatus.OK, response.statusCode)
    val body = response.body!!

    // verify routing
    val month = LocalDate.parse("2026-01-01")
    verify(service).getStatsForMonths(month, month)

    // totals mapping
    val total = body.total
    assertEquals(10L, total.totalSignedUp)
    assertEquals(7L, total.activeUsers)
    assertEquals(3L, total.inactiveUsers)
    assertEquals(20L, total.completedCheckins)
    assertEquals(2L, total.notCompletedOnTime)
    assertEquals(5.5, total.avgHoursToComplete)
    assertEquals(2.86, total.avgCompletedCheckinsPerPerson)
    assertEquals(0.7, total.pctActiveUsers)
    assertEquals(0.3, total.pctInactiveUsers)
    assertEquals(1.0, total.pctCompletedCheckins)
    assertEquals(0.1, total.pctExpiredCheckins)
    assertEquals(1.0, total.pctSignedUpOfTotal)
    assertEquals(totalsUpdatedAt.toString(), total.updatedAt)

    assertEquals(10L, total.feedbackTotal)
    assertEquals(howEasyCounts, total.howEasyCounts)
    assertEquals(howEasyPct, total.howEasyPct)
    assertEquals(gettingSupportCounts, total.gettingSupportCounts)
    assertEquals(gettingSupportPct, total.gettingSupportPct)
    assertEquals(improvementsCounts, total.improvementsCounts)
    assertEquals(improvementsPct, total.improvementsPct)

    // providers mapping
    assertEquals(2, body.providers.size)

    val first = body.providers[0]
    assertEquals("WPTNWS", first.providerCode)
    assertEquals("North Wales", first.providerDescription)
    assertEquals(4L, first.totalSignedUp)
    assertEquals(3L, first.activeUsers)
    assertEquals(1L, first.inactiveUsers)
    assertEquals(8L, first.completedCheckins)
    assertEquals(1L, first.notCompletedOnTime)
    assertEquals(4.25, first.avgHoursToComplete)
    assertEquals(2.0, first.avgCompletedCheckinsPerPerson)
    assertEquals(0.75, first.pctActiveUsers)
    assertEquals(0.25, first.pctInactiveUsers)
    assertEquals(0.8889, first.pctCompletedCheckins)
    assertEquals(0.1111, first.pctExpiredCheckins)
    assertEquals(0.4, first.pctSignedUpOfTotal)
    assertEquals(providerUpdatedAt.toString(), first.updatedAt)

    val second = body.providers[1]
    assertEquals("N07ALL", second.providerCode)
    assertEquals("All London", second.providerDescription)
    assertEquals(6L, second.totalSignedUp)
    assertEquals(4L, second.activeUsers)
    assertEquals(2L, second.inactiveUsers)
    assertEquals(12L, second.completedCheckins)
    assertEquals(1L, second.notCompletedOnTime)
    assertEquals(6.0, second.avgHoursToComplete)
    assertEquals(3.0, second.avgCompletedCheckinsPerPerson)
    assertEquals(0.6667, second.pctActiveUsers)
    assertEquals(0.3333, second.pctInactiveUsers)
    assertEquals(0.9231, second.pctCompletedCheckins)
    assertEquals(0.0769, second.pctExpiredCheckins)
    assertEquals(0.6, second.pctSignedUpOfTotal)
    assertEquals(providerUpdatedAt.toString(), second.updatedAt)
  }

  @Test
  fun `getStats - range params call getStatsForMonths and accepts single-sided range`() {
    val totalsUpdatedAt = Instant.parse("2026-01-28T12:02:00Z")

    val totals =
      StatsTotalsDto(
        totalSignedUp = 1,
        activeUsers = 1,
        inactiveUsers = 0,
        completedCheckins = 0,
        notCompletedOnTime = 0,
        avgHoursToComplete = 0.0,
        avgCompletedCheckinsPerPerson = 0.0,
        pctActiveUsers = 1.0,
        pctInactiveUsers = 0.0,
        pctCompletedCheckins = 0.0,
        pctExpiredCheckins = 0.0,
        feedbackTotal = 0,
        howEasyCounts = emptyMap(),
        howEasyPct = emptyMap(),
        gettingSupportCounts = emptyMap(),
        gettingSupportPct = emptyMap(),
        improvementsCounts = emptyMap(),
        improvementsPct = emptyMap(),
        pctSignedUpOfTotal = 1.0,
        updatedAt = totalsUpdatedAt,
      )

    whenever(service.getStatsForMonths(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-01")))
      .thenReturn(
        StatsDashboardDto(
          total = totals,
          providers = emptyList(),
        ),
      )

    val response = resource.getStats(fromMonth = "2026-01", toMonth = "2026-03")

    assertEquals(HttpStatus.OK, response.statusCode)
    verify(service).getStatsForMonths(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-01"))
  }

  @Test
  fun `getStats throws when invalid month format`() {
    val ex = assertThrows(BadArgumentException::class.java) {
      resource.getStats(fromMonth = "2026/01", toMonth = "2026-01")
    }
    assertEquals("Invalid month format '2026/01' (expected YYYY-MM)", ex.message)
  }

  @Test
  fun `getStats throws when fromMonth after toMonth`() {
    val ex = assertThrows(IllegalArgumentException::class.java) {
      resource.getStats(fromMonth = "2026-03", toMonth = "2026-01")
    }
    assertEquals("fromMonth must be < toMonth", ex.message)
  }
}
