package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.fasterxml.jackson.core.type.TypeReference
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.question.replacePlaceholder
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsProviderDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.SubmitTimeDistributionDto
import java.lang.reflect.ParameterizedType
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import java.util.stream.Stream

/**
 * Repository for V2 Offenders
 */
@Repository
interface OffenderRepository : JpaRepository<Offender, Long> {
  fun findByUuid(uuid: UUID): Optional<Offender>

  @Transactional(readOnly = true)
  fun findByCrn(crn: String): Optional<Offender>

  @Query(
    value = """
    SELECT
        o.id as id, 
        o.crn as crn, 
        o.practitioner_id as practitionerId, 
        o.contact_preference as contactPreference, 
        o.current_event as currentEvent FROM offender_v2 o
    WHERE o.status = 'VERIFIED'
      AND o.first_checkin <= :lowerBoundInclusive
      AND MOD(CAST(:lowerBoundInclusive - o.first_checkin AS integer), CAST(EXTRACT(DAY FROM o.checkin_interval) AS integer)) = 0
      AND NOT EXISTS (
        SELECT 1 FROM offender_checkin_v2 c
        WHERE c.offender_id = o.id
          AND :lowerBoundInclusive <= c.due_date
          AND c.due_date < :upperBoundExclusive
          AND c.status IN ('CREATED', 'SUBMITTED', 'REVIEWED')
      )
      AND o.id > coalesce(:lastOffenderId, 0)
    ORDER BY o.id
    FETCH FIRST :chunkSize ROWS ONLY
    """,
    nativeQuery = true,
  )
  fun findEligibleForCheckinCreation(
    lowerBoundInclusive: LocalDate,
    upperBoundExclusive: LocalDate,
    chunkSize: Int = 10,
    lastOffenderId: Long? = null,
  ): List<IOffenderCheckinCreationInfo>

  interface IOffenderCheckinCreationInfo : ActiveEvent {
    val id: Long
    val crn: CRN
    val practitionerId: ExternalUserId
    val contactPreference: ContactPreference
    override val currentEvent: Long?
  }

  /**
   * Find offenders whose next checkin due date matches specific offsets from :today
   * - Status = VERIFIED
   * - Next checkin due date matches (today + 1) OR (today + 4)
   * - No reminder sent yet for the next checkin
   * - No (upcoming) question list assignment for the offender exists
   */
  @Query(
    value = """
      with the_offenders as (
          select o.*, qla.id as question_list_assignment_id, gn.id as generic_notification_id
          from offender_v2 o
          left join question_list_assignment qla
              on qla.offender_id = o.id and qla.checkin_id is null
          left join generic_notification_v2 gn
              on gn.offender_id = o.id
                     and gn.event_type = :notificationType
                     and gn.created_at >= :reminderWindowStart
          where o.status = 'VERIFIED'
          and o.first_checkin != :today
          and (
          MOD(CAST(((cast(:today as date) + '1 day'::interval)::date - o.first_checkin) AS integer), CAST(EXTRACT(DAY FROM o.checkin_interval) AS integer)) = 0
              or
          MOD(CAST(((cast(:today as date) + '4 day'::interval)::date - o.first_checkin) AS integer), CAST(EXTRACT(DAY FROM o.checkin_interval) AS integer)) = 0
          )
      )
      select * from the_offenders
      where question_list_assignment_id is null and generic_notification_id is null;
    """,
    nativeQuery = true,
  )
  fun findEligibleForPractitionerCustomQuestionsReminder(
    @Param("today") today: LocalDate,
    @Param("notificationType") notificationType: String,
    @Param("reminderWindowStart") reminderWindowStart: Instant,
  ): Stream<Offender>

  @Query(
    """
    SELECT o FROM Offender o
    JOIN MigrationControl mc ON mc.crn = o.crn
    WHERE EXISTS (
        SELECT 1 FROM EventAudit a
        WHERE a.crn = o.crn AND a.eventType = 'SETUP_COMPLETED'
        AND a.notes like '%Created by migration from V1%'
    )
""",
  )
  fun findMigratedWithoutAudit(pageable: Pageable): List<Offender>
}

/**
 * Repository for V2 Offender Setup
 */
@Repository
interface OffenderSetupRepository : JpaRepository<OffenderSetup, Long> {
  @EntityGraph(attributePaths = ["offender"])
  fun findByUuid(uuid: UUID): Optional<OffenderSetup>

  @Query(
    """
    SELECT s FROM OffenderSetup s
    WHERE s.offender = :offender
    ORDER BY s.createdAt DESC
    LIMIT 1
  """,
  )
  fun findByOffender(offender: Offender): Optional<OffenderSetup>

  @Query(
    """
    SELECT s FROM OffenderSetup s
    JOIN FETCH s.offender o
    WHERE o.crn = :crn
    ORDER BY s.createdAt DESC
    LIMIT 1
  """,
  )
  fun findByCrn(crn: String): Optional<OffenderSetup>
}

/**
 * Repository for V2 Checkins
 */
@Repository
interface OffenderCheckinRepository : JpaRepository<OffenderCheckin, Long> {
  @EntityGraph(attributePaths = ["offender"])
  fun findByUuid(uuid: UUID): Optional<OffenderCheckin>
  fun findAllByOffenderAndStatus(offender: Offender, status: CheckinStatus): List<OffenderCheckin>

  @Query("""select c from OffenderCheckin c where c.id in :ids""")
  @EntityGraph(attributePaths = ["offender"])
  fun findAllByIds(ids: List<Long>): List<OffenderCheckin>

  @Query(
    """
    select o.crn
    from offender_checkin_v2 c
    join offender_v2 o on o.id = c.offender_id
    where c.id in :ids
     """,
    nativeQuery = true,
  )
  fun findCrnsByCheckinIds(ids: List<Long>): List<String>

  @Query(
    """
    UPDATE OffenderCheckin c
    SET c.status = :newStatus
    WHERE c.offender = :offender
      AND c.status = :currentStatus
    """,
  )
  @Modifying
  fun updateStatusForOffender(offender: Offender, currentStatus: CheckinStatus, newStatus: CheckinStatus): Int

  @Query(
    """
    SELECT c FROM OffenderCheckin c
    JOIN FETCH c.offender
    WHERE c.status = 'CREATED'
      AND c.dueDate <= :expiryDate
    """,
  )
  fun findEligibleForExpiry(expiryDate: LocalDate): Stream<OffenderCheckin>

  @Query(
    """
    SELECT c FROM OffenderCheckin c
    JOIN FETCH c.offender o
    WHERE c.status = 'CREATED'
      AND c.dueDate = :checkinStartDate
      AND NOT EXISTS (
          SELECT n FROM GenericNotification n
          WHERE n.offenderId = o.id
            AND n.eventType = :notificationType
            AND n.createdAt >= :checkinWindowStart
      )
    """,
  )
  fun findEligibleForReminder(
    @Param("checkinStartDate") checkinStartDate: LocalDate,
    @Param("notificationType") notificationType: String,
    @Param("checkinWindowStart") checkinWindowStart: Instant,
  ): Stream<OffenderCheckin>

  @Query(
    """
    SELECT c FROM OffenderCheckin c
    WHERE c.offender = :offender
      AND c.dueDate = :dueDate
    """,
  )
  fun findByOffenderAndDueDate(offender: Offender, dueDate: LocalDate): Optional<OffenderCheckin>

  /** Find all checkins by practitioner (created by) */
  @Query(
    """
    SELECT c FROM OffenderCheckin c
    JOIN FETCH c.offender o
    WHERE o.practitionerId = :practitionerId
      AND (:offenderUuid IS NULL OR o.uuid = :offenderUuid)
    """,
  )
  fun findAllByCreatedBy(
    practitionerId: ExternalUserId,
    offenderUuid: UUID?,
    pageable: Pageable,
  ): Page<OffenderCheckin>

  /** Find checkins needing attention (SUBMITTED or EXPIRED without review) */
  @Query(
    """
    SELECT c FROM OffenderCheckin c
    JOIN FETCH c.offender o
    WHERE o.practitionerId = :practitionerId
      AND (:offenderUuid IS NULL OR o.uuid = :offenderUuid)
      AND (c.status = 'SUBMITTED' OR (c.status = 'EXPIRED' AND c.reviewedAt IS NULL))
    """,
  )
  fun findNeedsAttention(
    practitionerId: ExternalUserId,
    offenderUuid: UUID?,
    pageable: Pageable,
  ): Page<OffenderCheckin>

  /** Find reviewed checkins (REVIEWED or EXPIRED with review) */
  @Query(
    """
    SELECT c FROM OffenderCheckin c
    JOIN FETCH c.offender o
    WHERE o.practitionerId = :practitionerId
      AND (:offenderUuid IS NULL OR o.uuid = :offenderUuid)
      AND (c.status = 'REVIEWED' OR (c.status = 'EXPIRED' AND c.reviewedAt IS NOT NULL))
    """,
  )
  fun findReviewed(
    practitionerId: ExternalUserId,
    offenderUuid: UUID?,
    pageable: Pageable,
  ): Page<OffenderCheckin>

  /** Find checkins awaiting offender submission (CREATED) */
  @Query(
    """
    SELECT c FROM OffenderCheckin c
    JOIN FETCH c.offender o
    WHERE o.practitionerId = :practitionerId
      AND (:offenderUuid IS NULL OR o.uuid = :offenderUuid)
      AND c.status = 'CREATED'
    """,
  )
  fun findAwaitingCheckin(
    practitionerId: ExternalUserId,
    offenderUuid: UUID?,
    pageable: Pageable,
  ): Page<OffenderCheckin>

  @Query(
    """
    SELECT c FROM OffenderCheckin c
    JOIN Offender o ON o = c.offender
    JOIN MigrationControl mc ON mc.crn = o.crn
    WHERE c.status = :status
    AND EXISTS (
        SELECT 1 FROM EventAudit a
        WHERE a.checkinUuid = c.uuid
        AND a.notes like '%Created by migration from V1%'
    )
""",
  )
  fun findMigratedByStatus(status: CheckinStatus, pageable: Pageable): List<OffenderCheckin>
}

/**
 * Repository for V2 Generic Notifications
 */
@Repository
interface GenericNotificationRepository : JpaRepository<GenericNotification, Long> {
  @Query(
    """
      SELECT COUNT(n) > 0 
      FROM GenericNotification n 
      WHERE n.offenderId = :#{#offender.id}
        AND n.eventType = :eventType 
        AND n.createdAt >= :cutoffTime
  """,
  )
  @Transactional(readOnly = true)
  fun hasNotificationBeenSent(
    offender: Offender,
    eventType: String,
    cutoffTime: Instant,
  ): Boolean
}

/**
 * Repository for V2 Event Audit Log
 */
@Repository
interface EventAuditRepository : JpaRepository<EventAudit, Long> {
  fun findAllByEventType(eventType: String): List<EventAudit>

  @Query(
    """
    SELECT a FROM EventAudit a
    WHERE a.crn in :crns
    ORDER BY a.occurredAt
    """,
  )
  fun findByCrnOrderByOccurredAt(crns: Set<String>): List<EventAudit>
}

/**
 * Repository for V2 Job Log
 * Separate from V1 job_log for complete decoupling
 */
@Repository
interface JobLogRepository : JpaRepository<JobLog, Long>

enum class LogEntryType {
  OFFENDER_SETUP_COMPLETE,
  OFFENDER_DEACTIVATED,
  OFFENDER_CHECKIN_NOT_SUBMITTED,
  OFFENDER_CHECKIN_REVIEW_SUBMITTED,
  OFFENDER_CHECKIN_ANNOTATED,
  OFFENDER_CHECKIN_RESCHEDULED,
  OFFENDER_CHECKIN_OUTSIDE_ACCESS,
  OFFENDER_CHECKIN_LIVENESS_FAILED,
  OFFENDER_CHECKIN_FACE_MATCH_FAILED,
}

/**
 * Repository for V2 offender event Log
 * Separate from V1 offender_event_log for complete decoupling
 */
@Repository
interface OffenderEventLogRepository : JpaRepository<OffenderEventLog, Long> {
  @Query(
    """
    select 
        e.uuid as uuid,
        e.comment as notes,
        e.createdAt as createdAt,
        e.logEntryType as logEntryType,
        e.practitioner as practitioner,
        c.uuid as checkin
    from OffenderEventLog e
    left join OffenderCheckin c on e.checkin = c.id
    where e.checkin is not null and c = :checkin and e.logEntryType in :ofType
    order by e.createdAt desc
  """,
  )
  fun findAllCheckinEvents(checkin: OffenderCheckin, ofType: Set<LogEntryType>): List<IOffenderCheckinLogEntryDto>

  @Query(
    """
          select 
        e.uuid as uuid,
        e.comment as notes,
        e.createdAt as createdAt,
        e.logEntryType as logEntryType,
        e.practitioner as practitioner,
        c.uuid as checkin
    from OffenderEventLog e
    left join OffenderCheckin c on e.checkin = c.id
    where e.uuid = :uuid and e.checkin is not null 
    """,
  )
  fun findCheckinLogByUuid(uuid: UUID): Optional<IOffenderCheckinLogEntryDto>

  fun findByUuid(uuid: UUID): Optional<OffenderEventLog>
}

/**
 * Repository for Feedback
 */
@Repository
interface FeedbackRepository : JpaRepository<Feedback, Long>

/**
 * Repository for Monthly Feedback Stats
 */
@Repository
interface TotalFeedbackMonthlyRepository : JpaRepository<TotalFeedbackMonthly, LocalDate>

@Component
class StatsSummaryRepository(
  private val jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate,
  private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
) {
  /**
   * @param fromMonth inclusive
   * @param toMonth exclusive
   */
  fun getFeedbackSummary(fromMonth: LocalDate, toMonth: LocalDate): TotalFeedbackSummary {
    require(fromMonth.isBefore(toMonth))

    return jdbcTemplate.queryForObject(
      "select * from get_total_feedback_summary(?, ?)",
      { rs, _ ->
        TotalFeedbackSummary(
          feedbackTotal = rs.getLong("feedback_total"),
          howEasyCounts = parseJsonMap(rs.getString("how_easy_counts")),
          howEasyPct = parseJsonBigDecimalMap(rs.getString("how_easy_pct")),
          gettingSupportCounts = parseJsonMap(rs.getString("getting_support_counts")),
          gettingSupportPct = parseJsonBigDecimalMap(rs.getString("getting_support_pct")),
          improvementsCounts = parseJsonMap(rs.getString("improvements_counts")),
          improvementsPct = parseJsonBigDecimalMap(rs.getString("improvements_pct")),
        )
      },
      fromMonth,
      toMonth,
    )!!
  }

  /**
   * Guaranteed to always return at least one row.
   *
   * When no stats data is available and `rowType` is `PROVIDER`, a catch-all row is returned with provider code `ALL`.
   *
   * @param fromMonth inclusive
   * @param toMonth exclusive
   */
  fun getSummary(fromMonth: LocalDate, toMonth: LocalDate, rowType: String): List<StatsProviderDto> {
    require(rowType == "ALL" || rowType == "PROVIDER")
    require(fromMonth.isBefore(toMonth))
    return jdbcTemplate.query(
      "select * from get_summary(?, ?, ?)",
      { rs, _ ->
        StatsProviderDto(
          providerCode = rs.getString("provider_code"),
          totalSignedUp = rs.getLong("total_signed_up"),
          totalActiveUsers = rs.getLong("total_active_users"),
          totalInactiveUsers = rs.getLong("total_inactive_users"),
          signedUp = rs.getLong("signed_up"),
          activeUsers = rs.getLong("active_users"),
          inactiveUsers = rs.getLong("inactive_users"),
          completedCheckins = rs.getLong("completed_checkins"),
          notCompletedOnTime = rs.getLong("expired_checkins"),
          avgHoursToComplete = rs.getBigDecimal("avg_hours_to_complete").toDouble(),
          avgCompletedCheckinsPerPerson = rs.getBigDecimal("avg_checkins_completed_per_person").toDouble(),
          pctTotalActiveUsers = rs.getBigDecimal("pct_active_users_total").toDouble(),
          pctTotalInactiveUsers = rs.getBigDecimal("pct_inactive_users_total").toDouble(),
          pctActiveUsers = rs.getBigDecimal("pct_active_users").toDouble(),
          pctInactiveUsers = rs.getBigDecimal("pct_inactive_users").toDouble(),
          pctCompletedCheckins = rs.getBigDecimal("pct_completed_checkins").toDouble(),
          pctExpiredCheckins = rs.getBigDecimal("pct_expired_checkins").toDouble(),
          providerDescription = if (rowType == "ALL") "" else rs.getString("provider_description"),
          pctSignedUpOfTotal = rs.getBigDecimal("pct_signed_up").toDouble(),
          updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
      },
      fromMonth,
      toMonth,
      rowType,
    )
  }

  /**
   * Median and 90th percentile of time-to-submit, plus the count of "delayed" submissions (start->submit gap
   * exceeding [DELAYED_SUBMISSION_THRESHOLD_HOURS]). Returns one row per provider plus an all row.
   *
   * Cached (on fromMonth/toMonth) to avoid a full event_audit_log_v2 scan on every request - TTL 6h
   */
  @Cacheable("stats.submit-time-distribution")
  fun getSubmitTimeDistribution(
    fromMonth: LocalDate,
    toMonth: LocalDate,
  ): List<SubmitTimeDistributionDto> {
    require(fromMonth.isBefore(toMonth))
    val sql = """
      SELECT
        COALESCE(provider_code, 'ALL') AS provider_code,
        COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY time_to_submit_hours) FILTER (WHERE time_to_submit_hours <= ${DELAYED_SUBMISSION_THRESHOLD_HOURS}), 0) AS median_hours,
        COALESCE(percentile_cont(0.9) WITHIN GROUP (ORDER BY time_to_submit_hours) FILTER (WHERE time_to_submit_hours <= ${DELAYED_SUBMISSION_THRESHOLD_HOURS}), 0) AS p90_hours,
        COUNT(*) FILTER (WHERE time_to_submit_hours > ?) AS delayed_submissions
      FROM event_audit_log_v2
      WHERE event_type = 'CHECKIN_SUBMITTED'
        AND occurred_at >= ?
        AND occurred_at < ?
        AND provider_code <> 'XXX'
        AND provider_code IS NOT NULL
        AND time_to_submit_hours IS NOT NULL
      GROUP BY GROUPING SETS ((), (provider_code))
    """.trimIndent()
    return jdbcTemplate.query(
      sql,
      { rs, _ ->
        SubmitTimeDistributionDto(
          providerCode = rs.getString("provider_code"),
          medianHoursToComplete = rs.getBigDecimal("median_hours").toDouble(),
          p90HoursToComplete = rs.getBigDecimal("p90_hours").toDouble(),
          delayedSubmissions = rs.getLong("delayed_submissions"),
        )
      },
      DELAYED_SUBMISSION_THRESHOLD_HOURS,
      fromMonth,
      toMonth,
    )
  }

  private companion object {
    /**
     * Submissions whose start->submit gap exceeds this many hours are counted as delayed (the offender starting check-in,
     * validated their identity then submitted much later), and should not contribute to the average time to complete a check-in.
     */
    const val DELAYED_SUBMISSION_THRESHOLD_HOURS = 12
  }

  private fun <T> parseJson(json: String?, typeReference: TypeReference<T>): T {
    if (json == null || json == "{}" || json == "null") {
      val type = typeReference.type
      val isMap = when (type) {
        is Class<*> -> Map::class.java.isAssignableFrom(type)
        is ParameterizedType -> Map::class.java.isAssignableFrom(type.rawType as Class<*>)
        else -> false
      }

      if (isMap) {
        return emptyMap<String, Any>() as T
      }
      throw IllegalArgumentException("Unsupported type: $type")
    }
    return objectMapper.readValue(json, typeReference)
  }

  private fun parseJsonMap(json: String?): Map<String, Long> = parseJson(json, object : TypeReference<Map<String, Long>>() {})

  private fun parseJsonBigDecimalMap(json: String?): Map<String, BigDecimal> = parseJson(json, object : TypeReference<Map<String, BigDecimal>>() {})
}

@Repository
interface StatsSummaryProviderMonthRepository : JpaRepository<StatsSummaryProviderMonth, StatsSummaryProviderMonthId>

@Repository
interface SetupEventBackfillRepository : JpaRepository<SetupEventBackfill, Long> {
  @Query(
    """
    SELECT b FROM SetupEventBackfill b
    WHERE b.setupRowCreated = false
    ORDER BY b.id
    """,
  )
  fun findPendingSetupRowCreation(pageable: Pageable): List<SetupEventBackfill>

  @Query(
    """
    SELECT b FROM SetupEventBackfill b
    WHERE b.eventSent = false
    ORDER BY b.id
    """,
  )
  fun findPendingEventSend(pageable: Pageable): List<SetupEventBackfill>
}

@Repository
interface MigrationControlRepository : JpaRepository<MigrationControl, Long>

@Repository
interface MigrationCheckinsUuidsRepository : JpaRepository<MigrationEventsToSend, Long>

@Repository
interface CheckinNoteResendRepository : JpaRepository<CheckinNoteResend, Long> {
  fun findBySentAtIsNull(pageable: Pageable): List<CheckinNoteResend>
}

@Repository
class QuestionRepository(
  private val jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate,
  private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
) {
  /**
   * Get questions for a specific list.
   *
   * Note: the result will contain the default questions (if any are defined).
   */
  fun getListItems(listId: Long, language: Language = Language.ENGLISH): List<QuestionListItemDto> = jdbcTemplate.query(
    "select * from get_question_list(?::integer, ?::text_language)",
    { rs, idx -> listItemRowMapper(rs, idx) },
    listId,
    language.dbString,
  )

  fun defaultListItems(language: Language): List<QuestionListItemDto> = jdbcTemplate.query(
    """
        with default_question_list as (
          select id as list_id from question_list where name = 'Default'
        )
        select * from get_question_list((select list_id from default_question_list), ?::text_language)
      """,
    { rs, idx -> listItemRowMapper(rs, idx) },
    language.dbString,
  )

  /**
   * Get a list of available question templates.
   */
  fun getQuestionTemplates(language: Language, author: ExternalUserId = "SYSTEM"): List<QuestionTemplateDto> {
    val result = jdbcTemplate.query(
      "select * from get_question_templates(?::text_language, ?) order by question_id",
      { rs, idx -> questionTemplateRowMapper(rs, idx) },
      language.dbString,
      author,
    )
    return result
  }

  /**
   * Returns question templates that shouldn't be shown to practitioners as a choice.
   */
  fun getFixedQuestionTemplates(language: Language, author: ExternalUserId = "SYSTEM"): List<QuestionTemplateDto> {
    val result = jdbcTemplate.query(
      "select * from get_question_templates(?::text_language, ?, 'FIXED'::question_policy)",
      { rs, idx -> questionTemplateRowMapper(rs, idx) },
      language.dbString,
      author,
    )
    return result
  }

  fun getQuestionTemplates(questionIds: List<Long>, language: Language): List<QuestionTemplateDto> {
    val result = jdbcTemplate.query(
      "select * from get_question_templates_by_ids(?, ?::text_language)",
      { rs, idx -> questionTemplateRowMapper(rs, idx) },
      questionIds.toTypedArray(),
      language.dbString,
    )
    return result
  }

  /**
   * Updates or creates a question list.
   */
  fun upsertQuestionList(listId: Long?, author: ExternalUserId, questions: List<Map<String, Any>>): Long? = jdbcTemplate.queryForObject(
    "select upsert_custom_question_list(?::bigint, ?::varchar(255), ?)",
    { rs, _ -> rs.getLong(1) },
    listId,
    author,
    objectMapper.writeValueAsString(questions),
  )

  private fun questionTemplateRowMapper(rs: ResultSet, idx: Int): QuestionTemplateDto = toQuestionTemplate(rs, idx)

  private fun listItemRowMapper(rs: ResultSet, idx: Int): QuestionListItemDto = QuestionListItemDto(
    template = toQuestionTemplate(rs, idx),
    params = asMap(rs, "params"),
  )

  private fun toQuestionTemplate(rs: ResultSet, idx: Int): QuestionTemplateDto {
    val template = rs.getString("question_template")
    val responseSpec = asMap(rs, "response_spec")
    val questionId = rs.getLong("question_id")

    // We're going to safely create question examples from the template,
    // We don't want to fail here because:
    // 1. Missing/invalid example should not stop us from returning data
    // 2. We have integration tests for the SYSTEM customisable questions that verify the examples get created
    val questionExamples = responseSpec["placeholders_examples"]?.let {
      if (it is List<*>) it else null
    }
      ?.map { evalExample(questionId, template, it) }
      ?.filter { !it.contains("{{") }

    return QuestionTemplateDto(
      id = questionId,
      policy = QuestionPolicy.fromString(rs.getString("policy")),
      template = template,
      responseFormat = QuestionResponseFormat.fromString(rs.getString("response_format")),
      responseSpec = responseSpec,
      example = rs.getString("example"),
      questionExamples = questionExamples,
    )
  }

  private fun asMap(rs: ResultSet, columnName: String): Map<String, Any> {
    val content = rs.getString(columnName) ?: return emptyMap()
    return objectMapper.readValue(
      content,
      object : TypeReference<Map<String, Any>>() {},
    )
  }

  companion object {
    val LOGGER = logger<QuestionRepository>()

    private fun evalExample(questionId: Long, template: String, replacement: Any?): String {
      try {
        var q = template
        if (replacement is Map<*, *>) {
          replacement.entries.forEach {
            val key = it.key
            val value = it.value
            if (key is String && value is String) {
              q = q.replacePlaceholder(key, value)
            } else {
              LOGGER.warn("evalExamples: Invalid replacement for questionId={}, key={}, value={}", questionId, key, value)
            }
          }
        } else {
          LOGGER.warn("evalExamples: Invalid replacement for questionId={}, replacement={}", questionId, replacement)
        }
        return q
      } catch (e: Exception) {
        // This will be filtered out
        // We also don't want to throw here, it's not a critical failure if an example string is missing
        LOGGER.warn("evalExamples: Failed to eval example for questionId={}, replacement={}: {}", questionId, replacement, e.message)
        return template
      }
    }
  }
}

@Repository
interface QuestionListAssignmentRepository : JpaRepository<QuestionListAssignment, Long> {

  interface AssignmentInfo {
    val questionListId: Long
    val dueDate: LocalDate
    val explicitAssignment: Boolean
  }

  @Query(
    """
    insert into question_list_assignment (question_list_id, offender_id, checkin_id, updated_at)
    select :listId, :offenderId, :checkinId, now()
    on conflict (offender_id) where checkin_id is null 
    do update set
        question_list_id = :listId,
        updated_at = now()
  """,
    nativeQuery = true,
  )
  @Modifying
  fun createAssignment(offenderId: Long, listId: Long, checkinId: Long? = null): Int

  /**
   * In case of no explicit assignment, question list id will be set to the default list id.
   */
  @Query(
    """select * from get_upcoming_assignment_info(:offenderId, cast(:nextCheckinDate as date), :checkinWindowDays)""",
    nativeQuery = true,
  )
  fun upcomingAssignmentAndDueDate(offenderId: Long, nextCheckinDate: LocalDate, checkinWindowDays: Long): AssignmentInfo

  /**
   * Returns the question list id for the checkin, if any.
   */
  @Query(
    """
    select qla.question_list_id from question_list_assignment qla
    where qla.checkin_id = :checkinId
  """,
    nativeQuery = true,
  )
  fun checkinAssignment(checkinId: Long): Long?

  @Query(
    """
    delete from question_list_assignment where offender_id = :offenderId and checkin_id is null
  """,
    nativeQuery = true,
  )
  @Modifying
  fun deleteUpcomingAssignment(offenderId: Long): Int
}

typealias OutboxItemTypeString = String

@Repository
interface OutboxItemRepository : JpaRepository<OutboxItem, Long> {

  @Query(
    """
      update outbox_items
      set status = 'SENT'::OutboxItemStatus, updated_at = now()
      where type = cast(:type as text)::OutboxItemType and entity_id = :entityId
    """,
    nativeQuery = true,
  )
  @Modifying
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun markAsSent(type: OutboxItemTypeString, entityId: Long): Int

  @Query
  fun findByTypeAndEntityId(type: OutboxItemType, entityId: Long): Optional<OutboxItem>
}
