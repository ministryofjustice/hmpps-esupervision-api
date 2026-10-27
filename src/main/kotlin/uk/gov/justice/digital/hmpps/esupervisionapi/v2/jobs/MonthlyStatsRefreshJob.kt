package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Date
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Component
class MonthlyStatsRefreshJob(
  private val jdbcTemplate: JdbcTemplate,
  private val clock: Clock,
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

      // The job runs once a day, so the previous month is refreshed too: otherwise events
      // after the last run on a month's final day would never be counted.
      refresh(monthStart.minusMonths(1), monthStart)
    } catch (e: Exception) {
      LOGGER.error("Stats Refresh Job failed", e)
    }
  }

  fun refresh(vararg monthStarts: LocalDate) {
    val overallStart = clock.instant()
    monthStarts.forEach { refreshMonth(it) }
    refreshViews()

    LOGGER.info(
      "Stats Refresh Job completed successfully, months={}, total duration={}",
      monthStarts.toList(),
      Duration.between(overallStart, clock.instant()),
    )
  }

  private fun refreshMonth(monthStart: LocalDate) {
    val rangeStart =
      monthStart
        .atStartOfDay(clock.zone)
        .toInstant()

    val rangeEnd =
      monthStart
        .plusMonths(1)
        .atStartOfDay(clock.zone)
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

    val monthlyFeedbackStart = clock.instant()
    jdbcTemplate.execute(REFRESH_MONTHLY_FEEDBACK_STATS_SQL) { ps ->
      ps.setDate(1, Date.valueOf(monthStart))
      ps.setObject(2, OffsetDateTime.ofInstant(rangeStart, ZoneOffset.UTC))
      ps.setObject(3, OffsetDateTime.ofInstant(rangeEnd, ZoneOffset.UTC))
      ps.execute()
    }
    val monthlyFeedbackEnd = clock.instant()

    LOGGER.info(
      "Monthly feedback stats table refreshed successfully, took={}",
      Duration.between(monthlyFeedbackStart, monthlyFeedbackEnd),
    )
  }

  private fun refreshViews() {
    val feedbackMvStart = clock.instant()
    jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY $FEEDBACK_MONTHLY_VIEW_NAME")
    val feedbackMvEnd = clock.instant()

    LOGGER.info(
      "Materialized view refreshed successfully: view={}, took={}",
      FEEDBACK_MONTHLY_VIEW_NAME,
      Duration.between(feedbackMvStart, feedbackMvEnd),
    )

    val monthlyMvStart = clock.instant()
    jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY $PROVIDER_MONTHLY_VIEW_NAME")
    val monthlyMvEnd = clock.instant()

    LOGGER.info(
      "Materialized view refreshed successfully: view={}, took={}",
      PROVIDER_MONTHLY_VIEW_NAME,
      Duration.between(monthlyMvStart, monthlyMvEnd),
    )
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(MonthlyStatsRefreshJob::class.java)

    const val REFRESH_MONTHLY_STATS_SQL = "SELECT refresh_monthly_stats(?, ?, ?)"
    const val REFRESH_MONTHLY_FEEDBACK_STATS_SQL = "SELECT refresh_monthly_feedback_stats(?, ?, ?)"

    const val FEEDBACK_MONTHLY_VIEW_NAME = "total_feedback_monthly"
    const val PROVIDER_MONTHLY_VIEW_NAME = "stats_summary_provider_month"
  }
}
