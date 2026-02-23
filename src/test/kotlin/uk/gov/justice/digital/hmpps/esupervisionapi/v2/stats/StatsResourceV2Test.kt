package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsDashboardDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsPduDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsResourceV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsServiceV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsTotalsDto
import java.math.BigDecimal
import java.time.Instant

class StatsResourceV2Test {

  private val service: StatsServiceV2 = mock()
  private val resource = StatsResourceV2(service)

  @Test
  fun `getStats returns expected StatsResponse with totals and pdus`() {
    val howEasyCounts =
      mapOf(
        "veryEasy" to 1L,
        "difficult" to 1L,
        "notAnswered" to 2L,
      )

    val howEasyPct =
      mapOf(
        "veryEasy" to BigDecimal("0.25"),
        "difficult" to BigDecimal("0.25"),
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
        "gettingHelp" to BigDecimal("0.5"),
        "checkInQuestions" to BigDecimal("1.0"),
      )

    val totalsUpdatedAt = Instant.parse("2026-01-28T12:02:00.020175Z")

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
        pctSignedUpOfTotal = 1.0, // totals are 100% of totals
        updatedAt = totalsUpdatedAt,
      )

    val pduUpdatedAt = Instant.parse("2026-01-28T12:02:00.000000Z")

    val pdu1 =
      StatsPduDto(
        pduCode = "WPTNWS",
        pduDescription = "North Wales",
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
        pctSignedUpOfTotal = 0.4, // 4/10
        updatedAt = pduUpdatedAt,
      )

    val pdu2 =
      StatsPduDto(
        pduCode = "N07ALL",
        pduDescription = "All London",
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
        pctSignedUpOfTotal = 0.6, // 6/10
        updatedAt = pduUpdatedAt,
      )

    whenever(service.getStats())
      .thenReturn(
        StatsDashboardDto(
          total = totals,
          pdus = listOf(pdu1, pdu2),
        ),
      )

    val response = resource.getStats()
    val body = response.body!!

    assertEquals(HttpStatus.OK, response.statusCode)

    val total = body.total
    assertEquals(10, total.totalSignedUp)
    assertEquals(7, total.activeUsers)
    assertEquals(3, total.inactiveUsers)
    assertEquals(20, total.completedCheckins)
    assertEquals(2, total.notCompletedOnTime)
    assertEquals(5.5, total.avgHoursToComplete)
    assertEquals(2.86, total.avgCompletedCheckinsPerPerson)
    assertEquals(0.7, total.pctActiveUsers)
    assertEquals(0.3, total.pctInactiveUsers)
    assertEquals(1.0, total.pctCompletedCheckins)
    assertEquals(0.1, total.pctExpiredCheckins)
    assertEquals(1.0, total.pctSignedUpOfTotal)
    assertEquals(totalsUpdatedAt.toString(), total.updatedAt)

    assertEquals(10, total.feedbackTotal)

    assertEquals(1L, total.howEasyCounts["veryEasy"])
    assertEquals(1L, total.howEasyCounts["difficult"])
    assertEquals(2L, total.howEasyCounts["notAnswered"])
    assertEquals(BigDecimal("0.25"), total.howEasyPct["veryEasy"])
    assertEquals(BigDecimal("0.25"), total.howEasyPct["difficult"])
    assertNull(total.howEasyPct["notAnswered"])

    assertEquals(2L, total.gettingSupportCounts["yes"])
    assertEquals(1L, total.gettingSupportCounts["no"])
    assertEquals(1L, total.gettingSupportCounts["notAnswered"])
    assertEquals(BigDecimal("0.6667"), total.gettingSupportPct["yes"])
    assertEquals(BigDecimal("0.3333"), total.gettingSupportPct["no"])
    assertNull(total.gettingSupportPct["notAnswered"])

    assertEquals(2L, total.improvementsCounts["checkInQuestions"])
    assertEquals(1L, total.improvementsCounts["gettingHelp"])
    assertEquals(1L, total.improvementsCounts["notAnswered"])
    assertEquals(BigDecimal("1.0"), total.improvementsPct["checkInQuestions"])
    assertEquals(BigDecimal("0.5"), total.improvementsPct["gettingHelp"])
    assertNull(total.improvementsPct["notAnswered"])

    assertEquals(2, body.pdus.size)

    val first = body.pdus[0]
    assertEquals("WPTNWS", first.pduCode)
    assertEquals("North Wales", first.pduDescription)
    assertEquals(4, first.totalSignedUp)
    assertEquals(3, first.activeUsers)
    assertEquals(1, first.inactiveUsers)
    assertEquals(8, first.completedCheckins)
    assertEquals(1, first.notCompletedOnTime)
    assertEquals(4.25, first.avgHoursToComplete)
    assertEquals(2.0, first.avgCompletedCheckinsPerPerson)
    assertEquals(0.75, first.pctActiveUsers)
    assertEquals(0.25, first.pctInactiveUsers)
    assertEquals(0.8889, first.pctCompletedCheckins)
    assertEquals(0.1111, first.pctExpiredCheckins)
    assertEquals(0.4, first.pctSignedUpOfTotal)
    assertEquals(pduUpdatedAt.toString(), first.updatedAt)

    val second = body.pdus[1]
    assertEquals("N07ALL", second.pduCode)
    assertEquals("All London", second.pduDescription)
    assertEquals(6, second.totalSignedUp)
    assertEquals(4, second.activeUsers)
    assertEquals(2, second.inactiveUsers)
    assertEquals(12, second.completedCheckins)
    assertEquals(1, second.notCompletedOnTime)
    assertEquals(6.0, second.avgHoursToComplete)
    assertEquals(3.0, second.avgCompletedCheckinsPerPerson)
    assertEquals(0.6667, second.pctActiveUsers)
    assertEquals(0.3333, second.pctInactiveUsers)
    assertEquals(0.9231, second.pctCompletedCheckins)
    assertEquals(0.0769, second.pctExpiredCheckins)
    assertEquals(0.6, second.pctSignedUpOfTotal)
    assertEquals(pduUpdatedAt.toString(), second.updatedAt)
  }
}