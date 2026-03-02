package uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.StatsSummaryProviderMonth
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.StatsSummaryProviderMonthRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.TotalFeedbackMonthly
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.TotalFeedbackMonthlyRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

data class StatsTotalsDto(
  val totalSignedUp: Long,
  val activeUsers: Long,
  val inactiveUsers: Long,
  val completedCheckins: Long,
  val notCompletedOnTime: Long,
  val avgHoursToComplete: Double,
  val avgCompletedCheckinsPerPerson: Double,
  val pctActiveUsers: Double,
  val pctInactiveUsers: Double,
  val pctCompletedCheckins: Double,
  val pctExpiredCheckins: Double,
  val feedbackTotal: Long,
  val howEasyCounts: Map<String, Long>,
  val howEasyPct: Map<String, BigDecimal>,
  val gettingSupportCounts: Map<String, Long>,
  val gettingSupportPct: Map<String, BigDecimal>,
  val improvementsCounts: Map<String, Long>,
  val improvementsPct: Map<String, BigDecimal>,
  val pctSignedUpOfTotal: Double,
  val updatedAt: Instant,
)

data class StatsProviderDto(
  val providerCode: String,
  val providerDescription: String,
  val totalSignedUp: Long,
  val activeUsers: Long,
  val inactiveUsers: Long,
  val completedCheckins: Long,
  val notCompletedOnTime: Long,
  val avgHoursToComplete: Double,
  val avgCompletedCheckinsPerPerson: Double,
  val pctActiveUsers: Double,
  val pctInactiveUsers: Double,
  val pctCompletedCheckins: Double,
  val pctExpiredCheckins: Double,
  val pctSignedUpOfTotal: Double,
  val updatedAt: Instant,
)

data class StatsDashboardDto(
  val total: StatsTotalsDto,
  val providers: List<StatsProviderDto>,
)

@Service
class StatsServiceV2(
  private val monthlyFeedbackRepository: TotalFeedbackMonthlyRepository,
  private val monthRepository: StatsSummaryProviderMonthRepository,
) {

  /**
   * Range mode using:
   * - stats_summary_provider_month (stats)
   * - total_feedback_monthly (feedback)
   *
   * fromMonth/toMonth are inclusive and should be first-of-month dates.
   *
   * - Stats "snapshot" totals come from the latest month WITH stats data in the requested range.
   * - Feedback is aggregated up to the latest month WITH feedback data in the requested range
   *   (so feedback may extend beyond stats).
   */
  @Transactional(readOnly = true)
  fun getStatsForMonths(fromMonth: LocalDate, toMonth: LocalDate): StatsDashboardDto {
    require(!fromMonth.isAfter(toMonth)) { "fromMonth must be <= toMonth" }

    val allRows = monthRepository.findAllBetween(fromMonth, toMonth).filterNotNull()
    val providerRows = monthRepository.findProvidersBetween(fromMonth, toMonth).filterNotNull()
    val feedbackRows = monthlyFeedbackRepository.findBetween(fromMonth, toMonth)

    // Truly nothing in either dataset
    if (allRows.isEmpty() && providerRows.isEmpty() && feedbackRows.isEmpty()) {
      return emptyDashboard()
    }

    // Work out independent "latest month" for each dataset
    val latestStatsMonth = allRows.maxOfOrNull { it.id.month }
    val latestFeedbackMonth = feedbackRows.maxOfOrNull { it.month }

    // Aggregate feedback up to latest feedback month (may extend beyond stats)
    val feedbackEnd = latestFeedbackMonth
    val feedbackInRange = if (feedbackEnd == null) emptyList() else feedbackRows.filter { !it.month.isAfter(feedbackEnd) }
    val feedback = aggregateFeedbackRows(feedbackInRange)

    // If there are no ALL stats rows at all, return empty stats + feedback
    if (latestStatsMonth == null) {
      val empty = emptyDashboard()
      return empty.copy(
        total = empty.total.copy(
          feedbackTotal = feedback.feedbackTotal,
          howEasyCounts = feedback.howEasyCounts,
          howEasyPct = feedback.howEasyPct,
          gettingSupportCounts = feedback.gettingSupportCounts,
          gettingSupportPct = feedback.gettingSupportPct,
          improvementsCounts = feedback.improvementsCounts,
          improvementsPct = feedback.improvementsPct,
        ),
      )
    }

    // Aggregate stats ONLY up to latest stats month
    val statsEnd = latestStatsMonth
    val allRowsInEffectiveRange = allRows.filter { !it.id.month.isAfter(statsEnd) }
    val providerRowsInEffectiveRange = providerRows.filter { !it.id.month.isAfter(statsEnd) }

    val endAllRow = allRowsInEffectiveRange.firstOrNull { it.id.month == statsEnd }
      ?: return emptyDashboard() // should not happen given statsEnd is derived from allRows

    val baseTotal = aggregateOverallRange(allRowsInEffectiveRange, endAllRow)
    val providers = aggregateProviderRange(
      providerRowsInEffectiveRange,
      statsEnd,
      overallTotalSignedUpAtEnd = baseTotal.totalSignedUp,
    )

    val totalWithFeedback = baseTotal.copy(
      feedbackTotal = feedback.feedbackTotal,
      howEasyCounts = feedback.howEasyCounts,
      howEasyPct = feedback.howEasyPct,
      gettingSupportCounts = feedback.gettingSupportCounts,
      gettingSupportPct = feedback.gettingSupportPct,
      improvementsCounts = feedback.improvementsCounts,
      improvementsPct = feedback.improvementsPct,
    )

    return StatsDashboardDto(
      total = totalWithFeedback,
      providers = providers,
    )
  }

  @Transactional(readOnly = true)
  fun getStatsForMonth(month: LocalDate): StatsDashboardDto = getStatsForMonths(month, month)

  // ----------------------------
  // Feedback aggregation
  // ----------------------------

  private data class FeedbackAgg(
    val feedbackTotal: Long,
    val howEasyCounts: Map<String, Long>,
    val howEasyPct: Map<String, BigDecimal>,
    val gettingSupportCounts: Map<String, Long>,
    val gettingSupportPct: Map<String, BigDecimal>,
    val improvementsCounts: Map<String, Long>,
    val improvementsPct: Map<String, BigDecimal>,
  )

  private fun aggregateFeedbackRows(rows: List<TotalFeedbackMonthly>): FeedbackAgg {
    if (rows.isEmpty()) {
      return FeedbackAgg(
        feedbackTotal = 0,
        howEasyCounts = emptyMap(),
        howEasyPct = emptyMap(),
        gettingSupportCounts = emptyMap(),
        gettingSupportPct = emptyMap(),
        improvementsCounts = emptyMap(),
        improvementsPct = emptyMap(),
      )
    }

    val feedbackTotal = rows.sumOf { it.feedbackTotal }

    val howEasyCounts = sumLongMaps(rows.map { it.howEasyCounts })
    val gettingSupportCounts = sumLongMaps(rows.map { it.gettingSupportCounts })
    val improvementsCounts = sumLongMaps(rows.map { it.improvementsCounts })

    return FeedbackAgg(
      feedbackTotal = feedbackTotal,
      howEasyCounts = howEasyCounts,
      howEasyPct = computePct(howEasyCounts),
      gettingSupportCounts = gettingSupportCounts,
      gettingSupportPct = computePct(gettingSupportCounts),
      improvementsCounts = improvementsCounts,
      improvementsPct = computePct(improvementsCounts),
    )
  }

  private fun sumLongMaps(maps: List<Map<String, Long>>): Map<String, Long> = maps
    .flatMap { it.entries }
    .groupBy({ it.key }, { it.value })
    .mapValues { (_, values) -> values.sum() }

  /**
   * Recompute pct from aggregated counts.
   * - excludes "notAnswered" from pct output
   * - denominator = feedbackTotal - notAnswered (min 1)
   */
  private fun computePct(
  counts: Map<String, Long>,
  ): Map<String, BigDecimal> {

    val notAnswered = counts["notAnswered"] ?: 0L
    val totalForQuestion = counts.values.sum()

    val denom = (totalForQuestion - notAnswered)
      .coerceAtLeast(1L)
      .toBigDecimal()

    return counts
      .filterKeys { it != "notAnswered" }
      .mapValues { (_, v) ->
        v.toBigDecimal()
          .divide(denom, 4, RoundingMode.HALF_UP)
      }
  }

  // ----------------------------
  // Stats aggregation
  // ----------------------------

  private fun aggregateOverallRange(
    allRows: List<StatsSummaryProviderMonth>,
    endRow: StatsSummaryProviderMonth,
  ): StatsTotalsDto {
    val completed = allRows.sumOf { it.completedCheckins }
    val expired = allRows.sumOf { it.notCompletedOnTime }
    val totalHours = allRows.fold(BigDecimal.ZERO) { acc, r -> acc + r.totalHoursToComplete }
    val uniqueCrns = allRows.sumOf { it.uniqueCheckinCrns }
    val updatedAt = allRows.maxOf { it.updatedAt }

    val avgHours = divToDouble(totalHours, completed)
    val avgPerPerson = divToDouble(BigDecimal.valueOf(completed), uniqueCrns)
    val (pctCompleted, pctExpired) = completedExpiredPct(completed, expired)

    return StatsTotalsDto(
      totalSignedUp = endRow.totalSignedUp,
      activeUsers = endRow.activeUsers,
      inactiveUsers = endRow.inactiveUsers,

      completedCheckins = completed,
      notCompletedOnTime = expired,

      avgHoursToComplete = avgHours,
      avgCompletedCheckinsPerPerson = avgPerPerson,

      pctActiveUsers = endRow.pctActiveUsers.toDouble(),
      pctInactiveUsers = endRow.pctInactiveUsers.toDouble(),
      pctCompletedCheckins = pctCompleted,
      pctExpiredCheckins = pctExpired,

      // feedback filled in by caller
      feedbackTotal = 0,
      howEasyCounts = emptyMap(),
      howEasyPct = emptyMap(),
      gettingSupportCounts = emptyMap(),
      gettingSupportPct = emptyMap(),
      improvementsCounts = emptyMap(),
      improvementsPct = emptyMap(),

      pctSignedUpOfTotal = 1.0,
      updatedAt = updatedAt,
    )
  }

  private fun aggregateProviderRange(
    providerRows: List<StatsSummaryProviderMonth>,
    toMonth: LocalDate,
    overallTotalSignedUpAtEnd: Long,
  ): List<StatsProviderDto> {
    if (providerRows.isEmpty()) return emptyList()

    val byProvider = providerRows.groupBy { requireNotNull(it.id.providerCode) }

    return byProvider.entries
      .map { (providerCode, rows) ->
        val endRow = rows.maxBy { it.id.month }

        val completed = rows.sumOf { it.completedCheckins }
        val expired = rows.sumOf { it.notCompletedOnTime }
        val totalHours = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.totalHoursToComplete }
        val uniqueCrns = rows.sumOf { it.uniqueCheckinCrns }
        val updatedAt = rows.maxOf { it.updatedAt }

        val avgHours = divToDouble(totalHours, completed)
        val avgPerPerson = divToDouble(BigDecimal.valueOf(completed), uniqueCrns)
        val (pctCompleted, pctExpired) = completedExpiredPct(completed, expired)

        val pctSignedUpOfTotal =
          if (overallTotalSignedUpAtEnd == 0L) {
            0.0
          } else {
            roundTo4(
              (endRow.totalSignedUp.toBigDecimal())
                .divide(overallTotalSignedUpAtEnd.toBigDecimal(), 4, RoundingMode.HALF_UP),
            ).toDouble()
          }

        StatsProviderDto(
          providerCode = providerCode,
          providerDescription = endRow.providerDescription
            ?: throw IllegalStateException(
              "PROVIDER row missing providerDescription for provider=$providerCode (latest month in range ending $toMonth)",
            ),

          totalSignedUp = endRow.totalSignedUp,
          activeUsers = endRow.activeUsers,
          inactiveUsers = endRow.inactiveUsers,

          completedCheckins = completed,
          notCompletedOnTime = expired,

          avgHoursToComplete = avgHours,
          avgCompletedCheckinsPerPerson = avgPerPerson,

          pctActiveUsers = endRow.pctActiveUsers.toDouble(),
          pctInactiveUsers = endRow.pctInactiveUsers.toDouble(),
          pctCompletedCheckins = pctCompleted,
          pctExpiredCheckins = pctExpired,

          pctSignedUpOfTotal = pctSignedUpOfTotal,
          updatedAt = updatedAt,
        )
      }
      .sortedBy { it.providerCode }
  }

  private fun divToDouble(numerator: BigDecimal, denominator: Long): Double {
    if (denominator == 0L) return 0.0
    return numerator
      .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
      .toDouble()
  }

  private fun completedExpiredPct(completed: Long, expired: Long): Pair<Double, Double> {
    val total = completed + expired
    if (total == 0L) return 0.0 to 0.0
    val pctCompleted = completed.toDouble() / total.toDouble()
    val pctExpired = expired.toDouble() / total.toDouble()
    return roundTo4(pctCompleted.toBigDecimal()).toDouble() to roundTo4(pctExpired.toBigDecimal()).toDouble()
  }

  private fun roundTo4(value: BigDecimal): BigDecimal = value.setScale(4, RoundingMode.HALF_UP)

  private fun emptyDashboard(): StatsDashboardDto = StatsDashboardDto(
    total = StatsTotalsDto(
      totalSignedUp = 0,
      activeUsers = 0,
      inactiveUsers = 0,
      completedCheckins = 0,
      notCompletedOnTime = 0,
      avgHoursToComplete = 0.0,
      avgCompletedCheckinsPerPerson = 0.0,
      pctActiveUsers = 0.0,
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
      updatedAt = Instant.now(),
    ),
    providers = emptyList(),
  )
}
