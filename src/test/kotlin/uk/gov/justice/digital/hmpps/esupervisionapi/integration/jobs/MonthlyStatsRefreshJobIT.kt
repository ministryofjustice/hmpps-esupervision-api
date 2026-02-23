package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MonthlyStatsRefreshJobIT : IntegrationTestBase() {

  @Autowired lateinit var jdbcTemplate: JdbcTemplate

  private val fixedClock: Clock =
    Clock.fixed(Instant.parse("2026-01-22T12:00:00Z"), ZoneOffset.UTC)

  @Test
  fun `refresh populates monthly tables and MV`() {

    // Insert audit events for 3 different PDUs
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

        (32, 'SETUP_COMPLETED', '2026-01-05T15:12:45.906', 'X968714', 'AutomatedTestUser',
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
      """
    )

    jdbcTemplate.update(
      """
      INSERT INTO feedback (feedback, created_at)
      VALUES
        ('{"version":1,"howEasy":"veryEasy","gettingSupport":"yes"}'::jsonb, '2026-01-08T10:00:00Z'),
        ('{"version":1,"gettingSupport":"no"}'::jsonb, '2026-01-11T10:00:00Z')
      """
    )

    val job = MonthlyStatsRefreshJob(jdbcTemplate, fixedClock)
    job.refresh()

    val monthlyStatsCount =
      jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM monthly_stats WHERE month = '2026-01-01'",
        Long::class.java,
      )

    // WPTNWS, N04ALL, N07ALL
    assertEquals(3L, monthlyStatsCount)

    // Assert one specific PDU row
    val northWales =
      jdbcTemplate.queryForMap(
        """
        SELECT * FROM monthly_stats
        WHERE month = '2026-01-01'
          AND pdu_code = 'WPTNWS'
        """
      )

    assertEquals(2L, northWales["users_activated"])
    assertEquals(0L, northWales["users_deactivated"])

    val feedbackRows =
      jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*)
        FROM monthly_feedback_stats
        WHERE month = '2026-01-01'
          AND feedback_version = 1
        """,
        Long::class.java,
      )
    assertEquals(3L, feedbackRows) // still 3 rows here for January, even though improvements has no answers

    // stats_summary_v1 now has 1 ALL row and 3 PDU rows
    val mvCount =
      jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM stats_summary_v1",
        Long::class.java,
      )
    assertEquals(4L, mvCount)

    val allRow =
      jdbcTemplate.queryForMap(
        """
        SELECT * FROM stats_summary_v1
        WHERE row_type = 'ALL'
        """
      )

    assertEquals(2L, allRow["feedback_total"])

    val pduRowCount =
      jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*)
        FROM stats_summary_v1
        WHERE row_type = 'PDU'
        """,
        Long::class.java,
      )

    assertEquals(3L, pduRowCount)
  }
}