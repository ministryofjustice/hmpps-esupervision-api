package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Date
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

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
      val now = clock.instant()
      val zone = clock.zone

      val monthStart =
        now.atZone(zone)
          .withDayOfMonth(1)
          .toLocalDate()

      val rangeStart =
        monthStart
          .atStartOfDay(zone)
          .toInstant()

      val rangeEnd =
        monthStart
          .plusMonths(1)
          .atStartOfDay(zone)
          .toInstant()

      val monthlyStart = clock.instant()
      jdbcTemplate.execute(REFRESH_MONTHLY_STATS_SQL) { ps ->
        ps.setDate(1, Date.valueOf(monthStart))
        ps.setObject(2, OffsetDateTime.ofInstant(rangeStart, ZoneOffset.UTC))
        ps.setObject(3, OffsetDateTime.ofInstant(rangeEnd, ZoneOffset.UTC))
        ps.execute()
      }
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

      LOGGER.info(
        "Stats Refresh Job completed successfully, total duration={}",
        Duration.between(overallStart, clock.instant()),
      )
    } catch (e: Exception) {
      LOGGER.error("Stats Refresh Job failed", e)
    }
  }

  companion object {
    private val LOGGER =
      LoggerFactory.getLogger(MonthlyStatsRefreshJob::class.java)

    val REFRESH_MONTHLY_STATS_SQL = "SELECT refresh_monthly_stats(?, ?, ?)"
  }
}
