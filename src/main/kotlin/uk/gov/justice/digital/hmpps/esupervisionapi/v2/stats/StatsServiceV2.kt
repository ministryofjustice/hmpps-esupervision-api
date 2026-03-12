package uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.StatsSummaryRepository
import java.math.BigDecimal
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
  private val statsSummaryRepository: StatsSummaryRepository,
) {

  /**
   * Calculates stats for given month range [fromMonth, toMonth) using the following tables:
   * - stats_summary_provider_month (stats)
   * - total_feedback_monthly (feedback)
   *
   *   @param fromMonth inclusive
   *   @param toMonth exclusive
   */
  @Transactional(readOnly = true)
  fun getStatsForMonths(fromMonth: LocalDate, toMonth: LocalDate): StatsDashboardDto {
    require(fromMonth.isBefore(toMonth)) { "fromMonth must be < toMonth" }

    val allRows = statsSummaryRepository.getSummary(fromMonth, toMonth, "ALL")
    val providerRows = statsSummaryRepository.getSummary(fromMonth, toMonth, "PROVIDER")
    val feedback = statsSummaryRepository.getFeedbackSummary(fromMonth, toMonth)

    val baseTotal = allRows.first().let {
      StatsTotalsDto(
        totalSignedUp = it.totalSignedUp,
        activeUsers = it.activeUsers,
        inactiveUsers = it.inactiveUsers,
        completedCheckins = it.completedCheckins,
        notCompletedOnTime = it.notCompletedOnTime,
        avgHoursToComplete = it.avgHoursToComplete,
        avgCompletedCheckinsPerPerson = it.avgCompletedCheckinsPerPerson,
        pctActiveUsers = it.pctActiveUsers,
        pctInactiveUsers = it.pctInactiveUsers,
        pctCompletedCheckins = it.pctCompletedCheckins,
        pctExpiredCheckins = it.pctExpiredCheckins,
        feedbackTotal = feedback.feedbackTotal,
        howEasyCounts = feedback.howEasyCounts,
        howEasyPct = feedback.howEasyPct,
        gettingSupportCounts = feedback.gettingSupportCounts,
        gettingSupportPct = feedback.gettingSupportPct,
        improvementsCounts = feedback.improvementsCounts,
        improvementsPct = feedback.improvementsPct,
        updatedAt = it.updatedAt,
        pctSignedUpOfTotal = it.pctSignedUpOfTotal,
      )
    }

    return StatsDashboardDto(total = baseTotal, providers = providerRows)
  }
}
