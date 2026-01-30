package uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.StatsSummaryRepository
import java.math.BigDecimal
import java.math.RoundingMode

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
)

@Service
class StatsServiceV2(
  private val repository: StatsSummaryRepository,
) {

  @Transactional(readOnly = true)
  fun getStats(): StatsWithPercentages {
    val stats = repository.findBySingleton(1)
      ?: throw IllegalStateException("Stats summary not found – materialised view stats_summary_v1 is empty")

    val totalUsers = stats.totalSignedUp.toDouble().takeIf { it > 0 } ?: 1.0
    val totalCheckins = (stats.completedCheckins + stats.notCompletedOnTime).toDouble().takeIf { it > 0 } ?: 1.0

    fun fourDecimals(value: Double) = BigDecimal(value).setScale(4, RoundingMode.HALF_UP).toDouble()

    return StatsWithPercentages(
      totalSignedUp = stats.totalSignedUp,
      activeUsers = stats.activeUsers,
      inactiveUsers = stats.inactiveUsers,
      completedCheckins = stats.completedCheckins,
      notCompletedOnTime = stats.notCompletedOnTime,
      avgHoursToComplete = fourDecimals(stats.avgHoursToComplete?.toDouble() ?: 0.0),
      avgCompletedCheckinsPerPerson = fourDecimals(stats.avgCompletedCheckinsPerPerson?.toDouble() ?: 0.0),
      updatedAt = stats.updatedAt,
      pctActiveUsers = fourDecimals(stats.activeUsers / totalUsers),
      pctInactiveUsers = fourDecimals(stats.inactiveUsers / totalUsers),
      pctCompletedCheckins = fourDecimals(stats.completedCheckins / totalCheckins),
      pctExpiredCheckins = fourDecimals(stats.notCompletedOnTime / totalCheckins),
    )
  }
}
