package uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.StatsSummary
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.StatsSummaryRepository
import java.math.BigDecimal
import java.time.Instant

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

data class StatsPduDto(
  val pduCode: String,
  val pduDescription: String,
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
  val pdus: List<StatsPduDto>,
)

@Service
class StatsServiceV2(
  private val repository: StatsSummaryRepository,
) {

  @Transactional(readOnly = true)
  fun getStats(): StatsDashboardDto {
    val overall = repository.findOverallRow()
      ?: throw IllegalStateException("Stats summary not found – materialised view stats_summary_v1 has no ALL row")

    val pdus = repository.findPduRows()

    return StatsDashboardDto(
      total = overall.toTotalsDto(),
      pdus = pdus.map { it.toPduDto() },
    )
  }

  private fun bdToDouble(value: java.math.BigDecimal?) = value?.toDouble() ?: 0.0

  private fun StatsSummary.toTotalsDto() = StatsTotalsDto(
    totalSignedUp = totalSignedUp,
    activeUsers = activeUsers,
    inactiveUsers = inactiveUsers,
    completedCheckins = completedCheckins,
    notCompletedOnTime = notCompletedOnTime,
    avgHoursToComplete = bdToDouble(avgHoursToComplete),
    avgCompletedCheckinsPerPerson = bdToDouble(avgCompletedCheckinsPerPerson),
    pctActiveUsers = bdToDouble(pctActiveUsers),
    pctInactiveUsers = bdToDouble(pctInactiveUsers),
    pctCompletedCheckins = bdToDouble(pctCompletedCheckins),
    pctExpiredCheckins = bdToDouble(pctExpiredCheckins),
    feedbackTotal = feedbackTotal,
    howEasyCounts = howEasyCounts,
    howEasyPct = howEasyPct,
    gettingSupportCounts = gettingSupportCounts,
    gettingSupportPct = gettingSupportPct,
    improvementsCounts = improvementsCounts,
    improvementsPct = improvementsPct,
    pctSignedUpOfTotal = bdToDouble(pctSignedUpOfTotal),
    updatedAt = updatedAt,
  )

  private fun StatsSummary.toPduDto() = StatsPduDto(
    pduCode = requireNotNull(id.pduCode) { "PDU row missing pduCode" },
    pduDescription = requireNotNull(pduDescription) { "PDU row missing pduDescription" },
    totalSignedUp = totalSignedUp,
    activeUsers = activeUsers,
    inactiveUsers = inactiveUsers,
    completedCheckins = completedCheckins,
    notCompletedOnTime = notCompletedOnTime,
    avgHoursToComplete = bdToDouble(avgHoursToComplete),
    avgCompletedCheckinsPerPerson = bdToDouble(avgCompletedCheckinsPerPerson),
    pctActiveUsers = bdToDouble(pctActiveUsers),
    pctInactiveUsers = bdToDouble(pctInactiveUsers),
    pctCompletedCheckins = bdToDouble(pctCompletedCheckins),
    pctExpiredCheckins = bdToDouble(pctExpiredCheckins),
    pctSignedUpOfTotal = bdToDouble(pctSignedUpOfTotal),
    updatedAt = updatedAt,
  )
}
