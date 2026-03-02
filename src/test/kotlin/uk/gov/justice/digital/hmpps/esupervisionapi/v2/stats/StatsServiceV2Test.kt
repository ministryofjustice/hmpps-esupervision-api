package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsProviderDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsServiceV2
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class StatsServiceV2Test {

  private val monthlyFeedbackRepository: TotalFeedbackMonthlyRepository = mock()
  private val monthRepository: StatsSummaryProviderMonthRepository = mock()

  private val service =
    StatsServiceV2(
      monthlyFeedbackRepository = monthlyFeedbackRepository,
      monthRepository = monthRepository,
    )

  @Test
  fun `getStatsForMonths throws when fromMonth after toMonth`() {
    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        service.getStatsForMonths(
          fromMonth = LocalDate.parse("2026-02-01"),
          toMonth = LocalDate.parse("2026-01-01"),
        )
      }

    assertEquals("fromMonth must be <= toMonth", exception.message)
  }

  @Test
  fun `getStatsForMonths returns empty dashboard when no stats or feedback exists`() {
    whenever(monthRepository.findAllBetween(any(), any())).thenReturn(emptyList())
    whenever(monthRepository.findProvidersBetween(any(), any())).thenReturn(emptyList())
    whenever(monthlyFeedbackRepository.findBetween(any(), any())).thenReturn(emptyList())

    val result =
      service.getStatsForMonths(
        fromMonth = LocalDate.parse("2026-01-01"),
        toMonth = LocalDate.parse("2026-01-01"),
      )

    assertEquals(0L, result.total.totalSignedUp)
    assertEquals(0L, result.total.activeUsers)
    assertEquals(0L, result.total.inactiveUsers)
    assertEquals(0L, result.total.completedCheckins)
    assertEquals(0L, result.total.notCompletedOnTime)
    assertEquals(0.0, result.total.avgHoursToComplete)
    assertEquals(0.0, result.total.avgCompletedCheckinsPerPerson)
    assertEquals(0.0, result.total.pctActiveUsers)
    assertEquals(0.0, result.total.pctInactiveUsers)
    assertEquals(0.0, result.total.pctCompletedCheckins)
    assertEquals(0.0, result.total.pctExpiredCheckins)

    assertEquals(0L, result.total.feedbackTotal)
    assertEquals(emptyMap<String, Long>(), result.total.howEasyCounts)
    assertEquals(emptyMap<String, BigDecimal>(), result.total.howEasyPct)
    assertEquals(emptyMap<String, Long>(), result.total.gettingSupportCounts)
    assertEquals(emptyMap<String, BigDecimal>(), result.total.gettingSupportPct)
    assertEquals(emptyMap<String, Long>(), result.total.improvementsCounts)
    assertEquals(emptyMap<String, BigDecimal>(), result.total.improvementsPct)

    assertEquals(emptyList<StatsProviderDto>(), result.providers)
  }

  @Test
  fun `getStatsForMonths returns feedback-only dashboard when stats missing but feedback exists`() {
    val fromMonth = LocalDate.parse("2026-01-01")
    val toMonth = LocalDate.parse("2026-02-01")

    whenever(monthRepository.findAllBetween(fromMonth, toMonth)).thenReturn(emptyList())
    whenever(monthRepository.findProvidersBetween(fromMonth, toMonth)).thenReturn(emptyList())

    val janFeedback =
      TotalFeedbackMonthly(
        month = LocalDate.parse("2026-01-01"),
        feedbackTotal = 4,
        howEasyCounts =
        mapOf(
          "veryEasy" to 1L,
          "difficult" to 1L,
          "notAnswered" to 2L,
        ),
        howEasyPct = emptyMap(), // ignored (service recomputes)
        gettingSupportCounts =
        mapOf(
          "yes" to 2L,
          "no" to 1L,
          "notAnswered" to 1L,
        ),
        gettingSupportPct = emptyMap(),
        improvementsCounts =
        mapOf(
          "gettingHelp" to 1L,
          "checkInQuestions" to 2L,
          "notAnswered" to 1L,
        ),
        improvementsPct = emptyMap(),
      )

    val febFeedback =
      TotalFeedbackMonthly(
        month = LocalDate.parse("2026-02-01"),
        feedbackTotal = 2,
        howEasyCounts =
        mapOf(
          "veryEasy" to 2L,
          "notAnswered" to 0L,
        ),
        howEasyPct = emptyMap(),
        gettingSupportCounts =
        mapOf(
          "yes" to 1L,
          "no" to 1L,
          "notAnswered" to 0L,
        ),
        gettingSupportPct = emptyMap(),
        improvementsCounts =
        mapOf(
          "notAnswered" to 2L,
        ),
        improvementsPct = emptyMap(),
      )

    whenever(monthlyFeedbackRepository.findBetween(fromMonth, toMonth)).thenReturn(listOf(janFeedback, febFeedback))

    val result = service.getStatsForMonths(fromMonth, toMonth)

    // Stats should be empty
    assertEquals(0L, result.total.totalSignedUp)
    assertEquals(0L, result.total.activeUsers)
    assertEquals(0L, result.total.inactiveUsers)
    assertEquals(0L, result.total.completedCheckins)
    assertEquals(0L, result.total.notCompletedOnTime)
    assertEquals(0.0, result.total.avgHoursToComplete)
    assertEquals(0.0, result.total.avgCompletedCheckinsPerPerson)
    assertEquals(0.0, result.total.pctCompletedCheckins)
    assertEquals(0.0, result.total.pctExpiredCheckins)
    assertEquals(emptyList<StatsProviderDto>(), result.providers)

    // Feedback aggregated across both months
    assertEquals(6L, result.total.feedbackTotal)

    val expectedHowEasyCounts =
      mapOf(
        "veryEasy" to 3L,
        "difficult" to 1L,
        "notAnswered" to 2L,
      )
    assertEquals(expectedHowEasyCounts, result.total.howEasyCounts)
    // denom = feedbackTotal - notAnswered = 6 - 2 = 4
    assertEquals(
      mapOf(
        "veryEasy" to BigDecimal("0.7500"),
        "difficult" to BigDecimal("0.2500"),
      ),
      result.total.howEasyPct,
    )

    val expectedGettingSupportCounts =
      mapOf(
        "yes" to 3L,
        "no" to 2L,
        "notAnswered" to 1L,
      )
    assertEquals(expectedGettingSupportCounts, result.total.gettingSupportCounts)
    // denom = 6 - 1 = 5
    assertEquals(
      mapOf(
        "yes" to BigDecimal("0.6000"),
        "no" to BigDecimal("0.4000"),
      ),
      result.total.gettingSupportPct,
    )

    val expectedImprovementsCounts =
      mapOf(
        "gettingHelp" to 1L,
        "checkInQuestions" to 2L,
        "notAnswered" to 3L,
      )
    assertEquals(expectedImprovementsCounts, result.total.improvementsCounts)
    // denom = 6 - 3 = 3
    assertEquals(
      mapOf(
        "gettingHelp" to BigDecimal("0.3333"),
        "checkInQuestions" to BigDecimal("0.6667"),
      ),
      result.total.improvementsPct,
    )
  }

  @Test
  fun `getStatsForMonths uses latest stats month for snapshot totals but sums range for completed and expired`() {
    val fromMonth = LocalDate.parse("2026-01-01")
    val toMonth = LocalDate.parse("2026-02-01")

    val updatedAtJan = Instant.parse("2026-01-28T12:02:00Z")
    val updatedAtFeb = Instant.parse("2026-02-15T09:30:00Z")

    // ALL rows (rowType=ALL, providerCode=NULL)
    val allJan =
      StatsSummaryProviderMonth(
        id =
        StatsSummaryProviderMonthId(
          rowType = "ALL",
          month = LocalDate.parse("2026-01-01"),
          providerCode = "",
        ),
        providerDescription = null,
        activeUsers = 7,
        inactiveUsers = 3,
        totalSignedUp = 10,
        completedCheckins = 20,
        notCompletedOnTime = 2,
        totalHoursToComplete = BigDecimal("110.0000"),
        uniqueCheckinCrns = 7,
        avgHoursToComplete = BigDecimal("0.0000"),
        avgCompletedCheckinsPerPerson = BigDecimal("0.0000"),
        pctActiveUsers = BigDecimal("0.7000"),
        pctInactiveUsers = BigDecimal("0.3000"),
        pctCompletedCheckins = BigDecimal("0.0000"),
        pctExpiredCheckins = BigDecimal("0.0000"),
        updatedAt = updatedAtJan,
      )

    val allFeb =
      StatsSummaryProviderMonth(
        id =
        StatsSummaryProviderMonthId(
          rowType = "ALL",
          month = LocalDate.parse("2026-02-01"),
          providerCode = "",
        ),
        providerDescription = null,
        activeUsers = 8,
        inactiveUsers = 4,
        totalSignedUp = 12, // snapshot values should come from latest month in range
        completedCheckins = 5,
        notCompletedOnTime = 1,
        totalHoursToComplete = BigDecimal("30.0000"),
        uniqueCheckinCrns = 5,
        avgHoursToComplete = BigDecimal("0.0000"),
        avgCompletedCheckinsPerPerson = BigDecimal("0.0000"),
        pctActiveUsers = BigDecimal("0.6667"),
        pctInactiveUsers = BigDecimal("0.3333"),
        pctCompletedCheckins = BigDecimal("0.0000"),
        pctExpiredCheckins = BigDecimal("0.0000"),
        updatedAt = updatedAtFeb,
      )

    whenever(monthRepository.findAllBetween(fromMonth, toMonth)).thenReturn(listOf(allJan, allFeb))

    // PROVIDER rows for two providers (rowType=PROVIDER, providerCode=code)
    val p1Jan =
      StatsSummaryProviderMonth(
        id =
        StatsSummaryProviderMonthId(
          rowType = "PROVIDER",
          month = LocalDate.parse("2026-01-01"),
          providerCode = "N03",
        ),
        providerDescription = "Wales",
        activeUsers = 3,
        inactiveUsers = 1,
        totalSignedUp = 4,
        completedCheckins = 8,
        notCompletedOnTime = 1,
        totalHoursToComplete = BigDecimal("34.0000"),
        uniqueCheckinCrns = 4,
        avgHoursToComplete = BigDecimal("0.0000"),
        avgCompletedCheckinsPerPerson = BigDecimal("0.0000"),
        pctActiveUsers = BigDecimal("0.7500"),
        pctInactiveUsers = BigDecimal("0.2500"),
        pctCompletedCheckins = BigDecimal("0.0000"),
        pctExpiredCheckins = BigDecimal("0.0000"),
        updatedAt = updatedAtJan,
      )

    val p1Feb =
      StatsSummaryProviderMonth(
        id =
        StatsSummaryProviderMonthId(
          rowType = "PROVIDER",
          month = LocalDate.parse("2026-02-01"),
          providerCode = "N03",
        ),
        providerDescription = "Wales",
        activeUsers = 4,
        inactiveUsers = 1,
        totalSignedUp = 5, // end-month snapshot for provider totals
        completedCheckins = 2,
        notCompletedOnTime = 0,
        totalHoursToComplete = BigDecimal("8.0000"),
        uniqueCheckinCrns = 2,
        avgHoursToComplete = BigDecimal("0.0000"),
        avgCompletedCheckinsPerPerson = BigDecimal("0.0000"),
        pctActiveUsers = BigDecimal("0.8000"),
        pctInactiveUsers = BigDecimal("0.2000"),
        pctCompletedCheckins = BigDecimal("0.0000"),
        pctExpiredCheckins = BigDecimal("0.0000"),
        updatedAt = updatedAtFeb,
      )

    val p2Jan =
      StatsSummaryProviderMonth(
        id =
        StatsSummaryProviderMonthId(
          rowType = "PROVIDER",
          month = LocalDate.parse("2026-01-01"),
          providerCode = "N07",
        ),
        providerDescription = "London",
        activeUsers = 4,
        inactiveUsers = 2,
        totalSignedUp = 6,
        completedCheckins = 12,
        notCompletedOnTime = 1,
        totalHoursToComplete = BigDecimal("76.0000"),
        uniqueCheckinCrns = 3,
        avgHoursToComplete = BigDecimal("0.0000"),
        avgCompletedCheckinsPerPerson = BigDecimal("0.0000"),
        pctActiveUsers = BigDecimal("0.6667"),
        pctInactiveUsers = BigDecimal("0.3333"),
        pctCompletedCheckins = BigDecimal("0.0000"),
        pctExpiredCheckins = BigDecimal("0.0000"),
        updatedAt = updatedAtJan,
      )

    val p2Feb =
      StatsSummaryProviderMonth(
        id =
        StatsSummaryProviderMonthId(
          rowType = "PROVIDER",
          month = LocalDate.parse("2026-02-01"),
          providerCode = "N07",
        ),
        providerDescription = "London",
        activeUsers = 4,
        inactiveUsers = 3,
        totalSignedUp = 7,
        completedCheckins = 3,
        notCompletedOnTime = 1,
        totalHoursToComplete = BigDecimal("22.0000"),
        uniqueCheckinCrns = 3,
        avgHoursToComplete = BigDecimal("0.0000"),
        avgCompletedCheckinsPerPerson = BigDecimal("0.0000"),
        pctActiveUsers = BigDecimal("0.5714"),
        pctInactiveUsers = BigDecimal("0.4286"),
        pctCompletedCheckins = BigDecimal("0.0000"),
        pctExpiredCheckins = BigDecimal("0.0000"),
        updatedAt = updatedAtFeb,
      )

    whenever(monthRepository.findProvidersBetween(fromMonth, toMonth)).thenReturn(listOf(p1Jan, p1Feb, p2Jan, p2Feb))

    // No feedback for this test
    whenever(monthlyFeedbackRepository.findBetween(fromMonth, toMonth)).thenReturn(emptyList())

    val result = service.getStatsForMonths(fromMonth, toMonth)

    // Snapshot totals come from latest ALL row (Feb)
    assertEquals(12L, result.total.totalSignedUp)
    assertEquals(8L, result.total.activeUsers)
    assertEquals(4L, result.total.inactiveUsers)
    assertEquals(0.6667, result.total.pctActiveUsers)
    assertEquals(0.3333, result.total.pctInactiveUsers)

    // Range sums across Jan+Feb
    assertEquals(25L, result.total.completedCheckins) // 20 + 5
    assertEquals(3L, result.total.notCompletedOnTime) // 2 + 1

    // pct completed/expired computed from summed completed+expired (25 and 3 => 28 total)
    assertEquals(0.8929, result.total.pctCompletedCheckins)
    assertEquals(0.1071, result.total.pctExpiredCheckins)

    // avgHoursToComplete = (110+30)/25 = 5.6
    assertEquals(5.6, result.total.avgHoursToComplete)

    // avgCompletedCheckinsPerPerson = 25 / (7+5=12) = 2.0833
    assertEquals(2.0833, result.total.avgCompletedCheckinsPerPerson)

    // updatedAt is max across ALL rows (Feb)
    assertEquals(updatedAtFeb, result.total.updatedAt)

    // Providers are sorted by providerCode
    val providers = result.providers
    assertEquals(listOf("N03", "N07"), providers.map { it.providerCode })

    val n03 = providers[0]
    assertEquals("Wales", n03.providerDescription)
    assertEquals(5L, n03.totalSignedUp) // end month snapshot
    assertEquals(4L, n03.activeUsers)
    assertEquals(1L, n03.inactiveUsers)
    assertEquals(10L, n03.completedCheckins) // 8 + 2
    assertEquals(1L, n03.notCompletedOnTime) // 1 + 0
    assertEquals(4.2, n03.avgHoursToComplete) // (34+8)/10 = 4.2
    assertEquals(1.6667, n03.avgCompletedCheckinsPerPerson) // 10/(4+2=6) = 1.6667
    assertEquals(0.9091, n03.pctCompletedCheckins) // 10/(10+1)
    assertEquals(0.0909, n03.pctExpiredCheckins)
    assertEquals(0.4167, n03.pctSignedUpOfTotal) // 5/12
    assertEquals(updatedAtFeb, n03.updatedAt)

    val n07 = providers[1]
    assertEquals("London", n07.providerDescription)
    assertEquals(7L, n07.totalSignedUp)
    assertEquals(4L, n07.activeUsers)
    assertEquals(3L, n07.inactiveUsers)
    assertEquals(15L, n07.completedCheckins) // 12 + 3
    assertEquals(2L, n07.notCompletedOnTime) // 1 + 1
    assertEquals(6.5333, n07.avgHoursToComplete) // (76+22)/15 = 6.5333
    assertEquals(2.5, n07.avgCompletedCheckinsPerPerson) // 15/(3+3=6)=2.5
    assertEquals(0.8824, n07.pctCompletedCheckins) // 15/(15+2)=15/17
    assertEquals(0.1176, n07.pctExpiredCheckins)
    assertEquals(0.5833, n07.pctSignedUpOfTotal) // 7/12
    assertEquals(updatedAtFeb, n07.updatedAt)
  }

  @Test
  fun `getStatsForMonths aggregates feedback up to latest feedback month even if feedback extends beyond latest stats month`() {
    val fromMonth = LocalDate.parse("2026-01-01")
    val toMonth = LocalDate.parse("2026-03-01")

    val updatedAtFeb = Instant.parse("2026-02-15T09:30:00Z")

    // Stats exist only in Feb
    val allFeb =
      StatsSummaryProviderMonth(
        id =
        StatsSummaryProviderMonthId(
          rowType = "ALL",
          month = LocalDate.parse("2026-02-01"),
          providerCode = "",
        ),
        providerDescription = null,
        activeUsers = 8,
        inactiveUsers = 4,
        totalSignedUp = 12,
        completedCheckins = 5,
        notCompletedOnTime = 1,
        totalHoursToComplete = BigDecimal("30.0000"),
        uniqueCheckinCrns = 5,
        avgHoursToComplete = BigDecimal("0.0000"),
        avgCompletedCheckinsPerPerson = BigDecimal("0.0000"),
        pctActiveUsers = BigDecimal("0.6667"),
        pctInactiveUsers = BigDecimal("0.3333"),
        pctCompletedCheckins = BigDecimal("0.0000"),
        pctExpiredCheckins = BigDecimal("0.0000"),
        updatedAt = updatedAtFeb,
      )

    whenever(monthRepository.findAllBetween(fromMonth, toMonth)).thenReturn(listOf(allFeb))
    whenever(monthRepository.findProvidersBetween(fromMonth, toMonth)).thenReturn(emptyList())

    // Feedback exists in Feb AND Mar (latestFeedbackMonth = Mar), should include both
    val febFeedback =
      TotalFeedbackMonthly(
        month = LocalDate.parse("2026-02-01"),
        feedbackTotal = 2,
        howEasyCounts = mapOf("veryEasy" to 1L, "notAnswered" to 1L),
        howEasyPct = emptyMap(),
        gettingSupportCounts = mapOf("yes" to 2L, "notAnswered" to 0L),
        gettingSupportPct = emptyMap(),
        improvementsCounts = mapOf("notAnswered" to 2L),
        improvementsPct = emptyMap(),
      )

    val marFeedback =
      TotalFeedbackMonthly(
        month = LocalDate.parse("2026-03-01"),
        feedbackTotal = 3,
        howEasyCounts = mapOf("difficult" to 2L, "notAnswered" to 1L),
        howEasyPct = emptyMap(),
        gettingSupportCounts = mapOf("no" to 3L, "notAnswered" to 0L),
        gettingSupportPct = emptyMap(),
        improvementsCounts = mapOf("checkInQuestions" to 1L, "notAnswered" to 2L),
        improvementsPct = emptyMap(),
      )

    whenever(monthlyFeedbackRepository.findBetween(fromMonth, toMonth)).thenReturn(listOf(febFeedback, marFeedback))

    val result = service.getStatsForMonths(fromMonth, toMonth)

    // Stats snapshot still from Feb
    assertEquals(12L, result.total.totalSignedUp)
    assertEquals(updatedAtFeb, result.total.updatedAt)

    // Feedback aggregated through Mar
    assertEquals(5L, result.total.feedbackTotal)

    // howEasy: veryEasy=1, difficult=2, notAnswered=2 => denom=5-2=3
    assertEquals(
      mapOf(
        "veryEasy" to 1L,
        "difficult" to 2L,
        "notAnswered" to 2L,
      ),
      result.total.howEasyCounts,
    )
    assertEquals(
      mapOf(
        "veryEasy" to BigDecimal("0.3333"),
        "difficult" to BigDecimal("0.6667"),
      ),
      result.total.howEasyPct,
    )
  }
}
