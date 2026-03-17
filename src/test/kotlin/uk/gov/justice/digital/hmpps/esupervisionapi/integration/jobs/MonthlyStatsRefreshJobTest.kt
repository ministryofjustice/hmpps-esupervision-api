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

  private val job = MonthlyStatsRefreshJob(jdbcTemplate, fixedClock)

  @Test
  fun `refresh calls monthly stats, monthly feedback stats, and both materialized views in order`() {
    job.refresh()

    val order = inOrder(jdbcTemplate)

    order.verify(jdbcTemplate).execute(
      eq(MonthlyStatsRefreshJob.REFRESH_MONTHLY_STATS_SQL),
      any<PreparedStatementCallback<*>>(),
    )

    order.verify(jdbcTemplate).execute(
      eq(MonthlyStatsRefreshJob.REFRESH_MONTHLY_FEEDBACK_STATS_SQL),
      any<PreparedStatementCallback<*>>(),
    )

    order.verify(jdbcTemplate)
      .execute("REFRESH MATERIALIZED VIEW CONCURRENTLY ${MonthlyStatsRefreshJob.FEEDBACK_MONTHLY_VIEW_NAME}")

    order.verify(jdbcTemplate)
      .execute("REFRESH MATERIALIZED VIEW CONCURRENTLY ${MonthlyStatsRefreshJob.PROVIDER_MONTHLY_VIEW_NAME}")
  }

  @Test
  fun `refresh does not throw when monthly stats function throws`() {
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

  @Test
  fun `refresh does not throw when monthly feedback stats function throws`() {
    doThrow(RuntimeException("DB error"))
      .whenever(jdbcTemplate)
      .execute(
        eq(MonthlyStatsRefreshJob.REFRESH_MONTHLY_FEEDBACK_STATS_SQL),
        any<PreparedStatementCallback<*>>(),
      )

    job.refresh()

    verify(jdbcTemplate).execute(
      eq(MonthlyStatsRefreshJob.REFRESH_MONTHLY_STATS_SQL),
      any<PreparedStatementCallback<*>>(),
    )

    verify(jdbcTemplate).execute(
      eq(MonthlyStatsRefreshJob.REFRESH_MONTHLY_FEEDBACK_STATS_SQL),
      any<PreparedStatementCallback<*>>(),
    )
  }

  @Test
  fun `refresh does not throw when feedback materialized view refresh throws`() {
    doThrow(RuntimeException("DB error"))
      .whenever(jdbcTemplate)
      .execute("REFRESH MATERIALIZED VIEW CONCURRENTLY ${MonthlyStatsRefreshJob.FEEDBACK_MONTHLY_VIEW_NAME}")

    job.refresh()

    verify(jdbcTemplate).execute(
      eq(MonthlyStatsRefreshJob.REFRESH_MONTHLY_STATS_SQL),
      any<PreparedStatementCallback<*>>(),
    )

    verify(jdbcTemplate).execute(
      eq(MonthlyStatsRefreshJob.REFRESH_MONTHLY_FEEDBACK_STATS_SQL),
      any<PreparedStatementCallback<*>>(),
    )

    verify(jdbcTemplate)
      .execute("REFRESH MATERIALIZED VIEW CONCURRENTLY ${MonthlyStatsRefreshJob.FEEDBACK_MONTHLY_VIEW_NAME}")
  }

  @Test
  fun `refresh does not throw when provider materialized view refresh throws`() {
    doThrow(RuntimeException("DB error"))
      .whenever(jdbcTemplate)
      .execute("REFRESH MATERIALIZED VIEW CONCURRENTLY ${MonthlyStatsRefreshJob.PROVIDER_MONTHLY_VIEW_NAME}")

    job.refresh()

    verify(jdbcTemplate).execute(
      eq(MonthlyStatsRefreshJob.REFRESH_MONTHLY_STATS_SQL),
      any<PreparedStatementCallback<*>>(),
    )

    verify(jdbcTemplate).execute(
      eq(MonthlyStatsRefreshJob.REFRESH_MONTHLY_FEEDBACK_STATS_SQL),
      any<PreparedStatementCallback<*>>(),
    )

    verify(jdbcTemplate)
      .execute("REFRESH MATERIALIZED VIEW CONCURRENTLY ${MonthlyStatsRefreshJob.PROVIDER_MONTHLY_VIEW_NAME}")
  }
}
