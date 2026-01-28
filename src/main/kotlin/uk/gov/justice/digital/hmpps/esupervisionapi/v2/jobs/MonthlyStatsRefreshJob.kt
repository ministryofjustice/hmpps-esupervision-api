package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
class MonthlyStatsRefreshJob(
  private val jdbcTemplate: JdbcTemplate,
  private val clock: Clock,
  @Value("\${app.scheduling.monthly-stats-refresh.view-name:stats_summary_v1}")
  private val viewName: String,
) {

  @Scheduled(cron = "\${app.scheduling.monthly-stats-refresh.cron}")
  @SchedulerLock(
    name = "Stats Refresh Job",
    lockAtLeastFor = "PT10S",
    lockAtMostFor = "PT30M",
  )
  fun refresh() {
    val overallStart = clock.instant()
    LOGGER.info("Stats Refresh Job started")

    try {
      val monthlyStart = clock.instant()
      jdbcTemplate.execute(REFRESH_MONTHLY_STATS_SQL)
      val monthlyEnd = clock.instant()
      LOGGER.info(
        "Monthly stats table refreshed successfully, took={}",
        Duration.between(monthlyStart, monthlyEnd),
      )

      val mvStart = clock.instant()
      jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY $viewName")
      val mvEnd = clock.instant()
      LOGGER.info(
        "Materialized view refreshed successfully: view={}, took={}",
        viewName,
        Duration.between(mvStart, mvEnd),
      )

      val overallEnd = clock.instant()
      LOGGER.info(
        "Stats Refresh Job completed successfully, total duration={}",
        Duration.between(overallStart, overallEnd),
      )
    } catch (e: Exception) {
      LOGGER.error("Stats Refresh Job failed", e)
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(MonthlyStatsRefreshJob::class.java)

    val REFRESH_MONTHLY_STATS_SQL = """
        SELECT refresh_monthly_stats(
            date_trunc('month', current_date)::date
        )
    """.trimIndent()
  }
}
