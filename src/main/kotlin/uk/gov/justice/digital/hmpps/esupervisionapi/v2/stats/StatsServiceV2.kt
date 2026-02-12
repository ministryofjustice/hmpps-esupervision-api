package uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.StatsSummaryRepository
import com.fasterxml.jackson.databind.JsonNode

data class StatsWithPercentages(
  val totalSignedUp: Long,
  val activeUsers: Long,
  val inactiveUsers: Long,
  val completedCheckins: Long,
  val notCompletedOnTime: Long,
  val avgHoursToComplete: Double,
  val avgCompletedCheckinsPerPerson: Double,
  val updatedAt: java.time.Instant,
  val pctActiveUsers: Double,
  val pctInactiveUsers: Double,
  val pctCompletedCheckins: Double,
  val pctExpiredCheckins: Double,
  val feedbackTotal: Long,
  val howEasyCounts: JsonNode,
  val howEasyPct: JsonNode,
  val gettingSupportCounts: JsonNode,
  val gettingSupportPct: JsonNode,
  val improvementsCounts: JsonNode,
  val improvementsPct: JsonNode,
)

@Service
class StatsServiceV2(
  private val repository: StatsSummaryRepository,
) {

  @Transactional(readOnly = true)
  fun getStats(): StatsWithPercentages {
    val stats = repository.findBySingleton(1)
      ?: throw IllegalStateException("Stats summary not found – materialised view stats_summary_v1 is empty")

    fun bdToDouble(value: java.math.BigDecimal?) = value?.toDouble() ?: 0.0

    return StatsWithPercentages(
      totalSignedUp = stats.totalSignedUp,
      activeUsers = stats.activeUsers,
      inactiveUsers = stats.inactiveUsers,
      completedCheckins = stats.completedCheckins,
      notCompletedOnTime = stats.notCompletedOnTime,
      avgHoursToComplete = bdToDouble(stats.avgHoursToComplete),
      avgCompletedCheckinsPerPerson = bdToDouble(stats.avgCompletedCheckinsPerPerson),
      updatedAt = stats.updatedAt,
      pctActiveUsers = bdToDouble(stats.pctActiveUsers),
      pctInactiveUsers = bdToDouble(stats.pctInactiveUsers),
      pctCompletedCheckins = bdToDouble(stats.pctCompletedCheckins),
      pctExpiredCheckins = bdToDouble(stats.pctExpiredCheckins),
      feedbackTotal = stats.feedbackTotal,
      howEasyCounts = stats.howEasyCounts,
      howEasyPct = stats.howEasyPct,
      gettingSupportCounts = stats.gettingSupportCounts,
      gettingSupportPct = stats.gettingSupportPct,
      improvementsCounts = stats.improvementsCounts,
      improvementsPct = stats.improvementsPct,
    )
  }
}
