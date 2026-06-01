package uk.gov.justice.digital.hmpps.esupervisionapi.integration.jobs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.StatsSummaryRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.MonthlyStatsRefreshJob
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MonthlyStatsRefreshJobIT : IntegrationTestBase() {

  @Autowired lateinit var jdbcTemplate: JdbcTemplate

  @Autowired lateinit var statsSummaryRepository: StatsSummaryRepository

  private val fixedClock: Clock =
    Clock.fixed(Instant.parse("2026-02-22T12:00:00Z"), ZoneOffset.UTC)

  @BeforeEach
  fun cleanDb() {
    jdbcTemplate.update("TRUNCATE TABLE event_audit_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE monthly_stats RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE monthly_feedback_stats RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE feedback RESTART IDENTITY CASCADE")
  }

  @Test
  fun `we get valid ZERO results event when no data is available`() {
    val job = MonthlyStatsRefreshJob(jdbcTemplate, fixedClock)
    job.refresh()

    val fromMonth = LocalDate.of(2026, 1, 1)
    val toMonth = LocalDate.of(2026, 4, 1)
    val allResult = statsSummaryRepository.getSummary(fromMonth, toMonth, "ALL")
    assertEquals(1, allResult.size)
    assertEquals(0, allResult.first().totalSignedUp)

    val providerResult = statsSummaryRepository.getSummary(fromMonth, toMonth, "PROVIDER")
    assertEquals(1, providerResult.size)
    assertEquals("ALL", providerResult.first().providerCode)

    val feedbackResult = statsSummaryRepository.getFeedbackSummary(fromMonth, toMonth)
    assertEquals(0, feedbackResult.feedbackTotal)
  }

  @Test
  fun `refresh populates monthly tables and MV`() {
    jdbcTemplate.update(
      """
      INSERT INTO event_audit_log_v2 (
        id, event_type, occurred_at, crn, practitioner_id,
        local_admin_unit_code, local_admin_unit_description,
        pdu_code, pdu_description,
        provider_code, provider_description,
        checkin_uuid, checkin_status, checkin_due_date,
        time_to_submit_hours,
        time_to_review_hours, review_duration_hours,
        auto_id_check_result, manual_id_check_result, notes
      )
      VALUES
        (1, 'SETUP_COMPLETED', '2026-01-05T10:00:00Z', 'X968714', 'AutomatedTestUser',
         'WPTNWS', 'North Wales', 'WPTNWS', 'North Wales',
         'N03', 'Wales', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),

        (32, 'SETUP_COMPLETED', '2026-01-05T15:12:45.906Z', 'X374635', 'AutomatedTestUser2',
         'WPTNWS', 'North Wales', 'WPTNWS', 'North Wales',
         'N03', 'Wales', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
        
        (120, 'SETUP_COMPLETED', '2026-02-03T11:12:45.000Z', 'X000011', 'AutomatedTestUser2',
         'WPTNWS', 'North Wales', 'WPTNWS', 'North Wales',
         'N03', 'Wales', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),

        (10, 'CHECKIN_SUBMITTED', '2026-01-09T14:14:11.695Z', 'X971072', 'P12345',
         'N04ALL', 'All NPS Midlands LDU', 'N04ALL', 'All NPS Midlands',
         'N04', 'NPS Midlands',
         '882bef16-97ae-4c34-a251-837845daa421'::uuid,
         'SUBMITTED', '2026-01-22', 1.97,
         NULL, NULL, 'MATCH', NULL, NULL),

        (11, 'CHECKIN_REVIEWED', '2026-01-09T14:21:57.482Z', 'X971072', 'P12345',
         'N04ALL', 'All NPS Midlands LDU', 'N04ALL', 'All NPS Midlands',
         'N04', 'NPS Midlands',
         '882bef16-97ae-4c34-a251-837845daa421'::uuid,
         'REVIEWED', '2026-01-25', NULL,
         NULL, NULL, NULL, 'MATCH', 'Reviewed'),

        (94, 'CHECKIN_EXPIRED', '2026-01-12T09:15:00.736Z', 'X971073', 'ABC123',
         'N07ALL', 'All London', 'N07ALL', 'All London',
         'N07', 'London',
         '927829aa-d061-4e6d-8cbb-50bbb691a2b3'::uuid,
         'EXPIRED', '2025-12-08',
         NULL, NULL, NULL, NULL, NULL, 'Expired')
      """,
    )

    jdbcTemplate.update(
      """
      INSERT INTO feedback (feedback, created_at)
      VALUES
        ('{"version":1,"howEasy":"veryEasy","gettingSupport":"yes"}'::jsonb, '2026-01-08T10:00:00Z'),
        ('{"version":1,"gettingSupport":"no"}'::jsonb, '2026-01-11T10:00:00Z'),
        ('{"version":1,"gettingSupport":"no"}'::jsonb, '2026-02-01T10:00:00Z')
      """,
    )

    val job = MonthlyStatsRefreshJob(jdbcTemplate, fixedClock)
    job.refresh(LocalDate.of(2026, 1, 1))
    job.refresh(LocalDate.of(2026, 2, 1))

    val monthlyStatsCount =
      jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM monthly_stats WHERE month = '2026-01-01'",
        Long::class.java,
      )!!

    // N03, N04, N07
    assertEquals(3L, monthlyStatsCount)

    val northWales =
      jdbcTemplate.queryForMap(
        """
        SELECT * FROM monthly_stats
        WHERE month = '2026-01-01'
          AND provider_code = 'N03'
        """,
      )

    assertEquals(2L, northWales["users_activated"])
    assertEquals(0L, northWales["users_deactivated"])

    val fromMonth = LocalDate.parse("2026-01-01")
    val toMonth = LocalDate.parse("2026-02-01")
    val feedbackSummary = statsSummaryRepository.getFeedbackSummary(fromMonth, toMonth)

    assertEquals(2L, feedbackSummary.feedbackTotal)
    assertEquals(1L, feedbackSummary.howEasyCounts["veryEasy"])
    assertEquals(0, BigDecimal("1.0000").compareTo(feedbackSummary.howEasyPct["veryEasy"]))
    assertEquals(1L, feedbackSummary.gettingSupportCounts["yes"])
    assertEquals(1L, feedbackSummary.gettingSupportCounts["no"])
    assertEquals(0, BigDecimal("0.5000").compareTo(feedbackSummary.gettingSupportPct["yes"]))
    assertEquals(0, BigDecimal("0.5000").compareTo(feedbackSummary.gettingSupportPct["no"]))
    assertEquals(2L, feedbackSummary.improvementsCounts["notAnswered"])
    assertEquals(0, feedbackSummary.improvementsPct.size)

    val feedbackRows =
      jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*)
        FROM monthly_feedback_stats
        WHERE month = '2026-01-01'
          AND feedback_version = 1
        """,
        Long::class.java,
      )!!
    assertEquals(3L, feedbackRows) // still 3 rows for January, even though improvements has no answers

    val allRow =
      jdbcTemplate.queryForList(
        """
        SELECT * FROM stats_summary_provider_month
        WHERE row_type = 'ALL'
        """,
      )

    assertEquals(2L, allRow[0]["active_users"])

    val providerRowCount =
      jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*)
        FROM stats_summary_provider_month
        WHERE row_type = 'PROVIDER'
          AND provider_code IN ('N03', 'N04', 'N07')
          AND month = '2026-01-01'
        """,
        Long::class.java,
      )!!

    assertEquals(3L, providerRowCount)

    val allSummary = statsSummaryRepository.getSummary(fromMonth, toMonth, "ALL").first()
    assertEquals(1, allSummary.completedCheckins)
    assertEquals(1, allSummary.notCompletedOnTime)

    val providerSummary = statsSummaryRepository.getSummary(fromMonth, toMonth, "PROVIDER")
    assertEquals(3, providerSummary.size)
    val midlandsSummary = providerSummary.find { it.providerCode == "N04" }!!
    assertEquals(1, midlandsSummary.completedCheckins)
    assertEquals(0, midlandsSummary.notCompletedOnTime)
    assertEquals(1.0, midlandsSummary.pctCompletedCheckins)
    assertEquals(0.0, midlandsSummary.pctExpiredCheckins)

    val extendedFeedbackSummary = statsSummaryRepository.getFeedbackSummary(fromMonth, toMonth.plusMonths(1))
    assertEquals(3, extendedFeedbackSummary.feedbackTotal)
    assertEquals(1L, extendedFeedbackSummary.gettingSupportCounts["yes"])
    assertEquals(2L, extendedFeedbackSummary.gettingSupportCounts["no"])
    assertEquals(BigDecimal("0.6667"), extendedFeedbackSummary.gettingSupportPct["no"])
  }

  @Test
  fun `refresh ignores excluded 'XXX' provider code`() {
    // This provider should be excluded by the refresh_monthly_stats function filter
    jdbcTemplate.update(
      """
      INSERT INTO event_audit_log_v2 (
        id, event_type, occurred_at, crn, practitioner_id,
        local_admin_unit_code, local_admin_unit_description,
        pdu_code, pdu_description,
        provider_code, provider_description,
        checkin_uuid, checkin_status, checkin_due_date,
        time_to_submit_hours,
        time_to_review_hours, review_duration_hours,
        auto_id_check_result, manual_id_check_result, notes
      )
      VALUES
        (2001, 'SETUP_COMPLETED', '2026-01-06T10:00:00Z', 'X000001', 'AutomatedTestUser',
         'ZZZZZZ', 'ZZ Test', 'ZZZZZZ', 'ZZ Test',
         'XXX', 'ZZ BAST Public Provider 1', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),

        (2002, 'CHECKIN_SUBMITTED', '2026-01-10T10:00:00Z', 'X000002', 'P99999',
         'ZZZZZZ', 'ZZ Test', 'ZZZZZZ', 'ZZ Test',
         'XXX', 'ZZ BAST Public Provider 1',
         '11111111-1111-1111-1111-111111111111'::uuid,
         'SUBMITTED', '2026-01-22', 2.5,
         NULL, NULL, 'MATCH', NULL, NULL)
      """,
    )

    val job = MonthlyStatsRefreshJob(jdbcTemplate, fixedClock)
    job.refresh()

    // Should NOT create a row for the excluded provider
    val excludedProviderRows =
      jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*)
        FROM monthly_stats
        WHERE month = '2026-01-01'
          AND provider_code = 'XXX'
        """,
        Long::class.java,
      )!!
    assertEquals(0L, excludedProviderRows)

    // January should still be empty (we inserted only XXX events)
    val monthlyStatsCount =
      jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM monthly_stats WHERE month = '2026-01-01'",
        Long::class.java,
      )!!
    assertEquals(0L, monthlyStatsCount)
  }
}
