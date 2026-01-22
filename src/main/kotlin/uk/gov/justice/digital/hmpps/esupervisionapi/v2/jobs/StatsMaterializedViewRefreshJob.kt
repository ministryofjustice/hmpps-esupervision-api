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
class StatsMaterializedViewRefreshJob(
  private val jdbcTemplate: JdbcTemplate,
  private val clock: Clock,
  @Value("\${app.scheduling.stats-refresh.view-name:stats_summary}")
  private val viewName: String,
) {

  @Scheduled(cron = "\${app.scheduling.stats-refresh.cron}")
  @SchedulerLock(
    name = "Stats Materialized View Refresh Job",
    lockAtLeastFor = "PT10S",
    lockAtMostFor = "PT30M",
  )
  fun refresh() {
    val start = clock.instant()

    LOGGER.info(
      "Stats Materialized View Refresh Job started: refreshing view={}",
      viewName,
    )

    try {
      jdbcTemplate.execute(
        "REFRESH MATERIALIZED VIEW CONCURRENTLY $viewName"
      )

      val end = clock.instant()
      LOGGER.info(
        "Stats Materialized View Refresh Job completed: view={}, took={}",
        viewName,
        Duration.between(start, end),
      )
    } catch (e: Exception) {
      LOGGER.error(
        "Stats Materialized View Refresh Job failed: view={}",
        viewName,
        e,
      )
    }
  }

  companion object {
    private val LOGGER =
      LoggerFactory.getLogger(StatsMaterializedViewRefreshJob::class.java)
  }
}
