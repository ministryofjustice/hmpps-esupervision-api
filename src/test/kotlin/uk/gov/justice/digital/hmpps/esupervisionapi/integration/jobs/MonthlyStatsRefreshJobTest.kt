package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCallback
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MonthlyStatsRefreshJobTest {

  private val jdbcTemplate: JdbcTemplate = mock()
  private val fixedClock: Clock =
    Clock.fixed(Instant.parse("2026-01-22T12:00:00Z"), ZoneOffset.UTC)

  private val viewName = "stats_summary_v1"
  private val job = MonthlyStatsRefreshJob(jdbcTemplate, fixedClock, viewName)

  @Test
  fun `refresh calls monthly stats function and materialized view in order`() {
    job.refresh()

    val order = inOrder(jdbcTemplate)

    order.verify(jdbcTemplate).execute(
      eq(MonthlyStatsRefreshJob.REFRESH_MONTHLY_STATS_SQL),
      any<PreparedStatementCallback<*>>(),
    )

    order.verify(jdbcTemplate)
      .execute("REFRESH MATERIALIZED VIEW CONCURRENTLY $viewName")
  }

  @Test
  fun `refresh logs and does not throw when JdbcTemplate throws`() {
    doThrow(RuntimeException("DB error"))
      .whenever(jdbcTemplate)
      .execute(
        eq(MonthlyStatsRefreshJob.REFRESH_MONTHLY_STATS_SQL),
        any<PreparedStatementCallback<*>>(),
      )

    job.refresh()

    verify(jdbcTemplate).execute(
      eq(MonthlyStatsRefreshJob.REFRESH_MONTHLY_STATS_SQL),
      any<PreparedStatementCallback<*>>(),
    )
  }
}
