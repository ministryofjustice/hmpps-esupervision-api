package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.fasterxml.jackson.core.type.TypeReference
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsProviderDto
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
interface OffenderV2Repository : JpaRepository<OffenderV2, Long> {
  fun findByUuid(uuid: UUID): Optional<OffenderV2>
  fun findByCrn(crn: String): Optional<OffenderV2>
  fun findAllByPractitionerId(practitionerId: ExternalUserId, pageable: Pageable): Page<OffenderV2>
  fun findAllByStatus(status: OffenderStatus): List<OffenderV2>

  /**
   * Find offenders eligible for checkin creation
   * - Status = VERIFIED
   * - Next checkin due date is today or earlier
   */
  @Query(
    value = """
    SELECT o.* FROM offender_v2 o
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
    """,
    nativeQuery = true,
  )
  fun findEligibleForCheckinCreation(
    lowerBoundInclusive: LocalDate,
    upperBoundExclusive: LocalDate,
  ): Stream<OffenderV2>

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
  ): Stream<OffenderV2>

  @Query("SELECT o.crn FROM OffenderV2 o WHERE o IN :offenders")
  fun getCrnsForOffenders(offenders: List<OffenderV2>): List<String>

  @Query(
    """
    SELECT o FROM OffenderV2 o
    JOIN MigrationControl mc ON mc.crn = o.crn
    WHERE EXISTS (
        SELECT 1 FROM EventAuditV2 a
        WHERE a.crn = o.crn AND a.eventType = 'SETUP_COMPLETED'
        AND a.notes like '%Created by migration from V1%'
    )
""",
  )
  fun findMigratedWithoutAudit(pageable: Pageable): List<OffenderV2>
}

/**
 * Repository for V2 Offender Setup
 */
@Repository
interface OffenderSetupV2Repository : JpaRepository<OffenderSetupV2, Long> {
  fun findByUuid(uuid: UUID): Optional<OffenderSetupV2>
  fun findByOffender(offender: OffenderV2): Optional<OffenderSetupV2>
  fun findAllByPractitionerId(practitionerId: ExternalUserId): List<OffenderSetupV2>

  @Query(
    """
    SELECT s FROM OffenderSetupV2 s
    JOIN FETCH s.offender o
    WHERE o.crn = :crn
    ORDER BY s.createdAt DESC
  """,
  )
  fun findByCrn(crn: String): Optional<OffenderSetupV2>
}

/**
 * Repository for V2 Checkins
 */
@Repository
interface OffenderCheckinV2Repository : JpaRepository<OffenderCheckinV2, Long> {
  fun findByUuid(uuid: UUID): Optional<OffenderCheckinV2>
  fun findAllByOffender(offender: OffenderV2, pageable: Pageable): Page<OffenderCheckinV2>
  fun findAllByOffenderAndStatus(offender: OffenderV2, status: CheckinV2Status): List<OffenderCheckinV2>
  fun findAllByStatus(status: CheckinV2Status): List<OffenderCheckinV2>

  @Query(
    """
    SELECT c FROM OffenderCheckinV2 c
    JOIN FETCH c.offender
    WHERE c.status = 'CREATED'
      AND c.dueDate <= :expiryDate
    """,
  )
  fun findEligibleForExpiry(expiryDate: LocalDate): Stream<OffenderCheckinV2>

  @Query(
    """
    SELECT c FROM OffenderCheckinV2 c
    JOIN FETCH c.offender o
    WHERE c.status = 'CREATED'
      AND c.dueDate = :checkinStartDate
      AND NOT EXISTS (
          SELECT n FROM GenericNotificationV2 n
          WHERE n.offender = o
            AND n.eventType = :notificationType
            AND n.createdAt >= :checkinWindowStart
      )
    """,
  )
  fun findEligibleForReminder(
    @Param("checkinStartDate") checkinStartDate: LocalDate,
    @Param("notificationType") notificationType: String,
    @Param("checkinWindowStart") checkinWindowStart: Instant,
  ): Stream<OffenderCheckinV2>

  @Query(
    """
    SELECT c FROM OffenderCheckinV2 c
    WHERE c.offender = :offender
      AND c.dueDate = :dueDate
    """,
  )
  fun findByOffenderAndDueDate(offender: OffenderV2, dueDate: LocalDate): Optional<OffenderCheckinV2>

  /**
   * Batch find checkins for multiple offenders on a specific due date
   * Used to avoid N+1 queries in batch processing
   */
  @Query(
    """
    SELECT c FROM OffenderCheckinV2 c
    WHERE c.offender IN :offenders
      AND c.dueDate = :dueDate
    """,
  )
  fun findByOffendersAndDueDate(offenders: List<OffenderV2>, dueDate: LocalDate): List<OffenderCheckinV2>

  fun existsByOffenderAndDueDate(offender: OffenderV2, dueDate: LocalDate): Boolean

  /** Find all checkins by practitioner (created by) */
  @Query(
    """
    SELECT c FROM OffenderCheckinV2 c
    JOIN FETCH c.offender o
    WHERE o.practitionerId = :practitionerId
      AND (:offenderUuid IS NULL OR o.uuid = :offenderUuid)
    """,
  )
  fun findAllByCreatedBy(
    practitionerId: ExternalUserId,
    offenderUuid: UUID?,
    pageable: Pageable,
  ): Page<OffenderCheckinV2>

  /** Find checkins needing attention (SUBMITTED or EXPIRED without review) */
  @Query(
    """
    SELECT c FROM OffenderCheckinV2 c
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
  ): Page<OffenderCheckinV2>

  /** Find reviewed checkins (REVIEWED or EXPIRED with review) */
  @Query(
    """
    SELECT c FROM OffenderCheckinV2 c
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
  ): Page<OffenderCheckinV2>

  /** Find checkins awaiting offender submission (CREATED) */
  @Query(
    """
    SELECT c FROM OffenderCheckinV2 c
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
  ): Page<OffenderCheckinV2>

  @Query(
    """
    SELECT c FROM OffenderCheckinV2 c
    JOIN OffenderV2 o ON o = c.offender
    JOIN MigrationControl mc ON mc.crn = o.crn
    WHERE c.status = :status
    AND EXISTS (
        SELECT 1 FROM EventAuditV2 a
        WHERE a.checkinUuid = c.uuid
        AND a.notes like '%Created by migration from V1%'
    )
""",
  )
  fun findMigratedByStatus(status: CheckinV2Status, pageable: Pageable): List<OffenderCheckinV2>
}

/**
 * Repository for V2 Generic Notifications
 */
@Repository
interface GenericNotificationV2Repository : JpaRepository<GenericNotificationV2, Long> {
  fun findByNotificationId(notificationId: UUID): Optional<GenericNotificationV2>
  fun findAllByOffender(offender: OffenderV2): List<GenericNotificationV2>
  fun findAllByEventType(eventType: String): List<GenericNotificationV2>
  fun findAllByReference(reference: String): List<GenericNotificationV2>

  @Query(
    """
    SELECT n FROM GenericNotificationV2 n
    WHERE n.offender = :offender
      AND n.eventType = :eventType
    ORDER BY n.createdAt DESC
    """,
  )
  fun findByOffenderAndEventType(offender: OffenderV2, eventType: String): List<GenericNotificationV2>

  @Query(
    """
      SELECT COUNT(n) > 0 
      FROM GenericNotificationV2 n 
      WHERE n.offender = :offender 
        AND n.eventType = :eventType 
        AND n.createdAt >= :cutoffTime
  """,
  )
  fun hasNotificationBeenSent(
    offender: OffenderV2,
    eventType: String,
    cutoffTime: Instant,
  ): Boolean
}

/**
 * Repository for V2 Event Audit Log
 */
@Repository
interface EventAuditV2Repository : JpaRepository<EventAuditV2, Long> {
  fun findAllByEventType(eventType: String): List<EventAuditV2>

  @Query(
    """
    SELECT a FROM EventAuditV2 a
    WHERE a.crn in :crns
    ORDER BY a.occurredAt
    """,
  )
  fun findByCrnOrderByOccurredAt(crns: Set<String>): List<EventAuditV2>

  @Query(
    """
    SELECT a FROM EventAuditV2 a
    WHERE a.occurredAt >= :startDate
      AND a.occurredAt <= :endDate
    ORDER BY a.occurredAt
    """,
  )
  fun findByOccurredAtBetween(startDate: Instant, endDate: Instant): List<EventAuditV2>
}

/**
 * Repository for V2 Job Log
 * Separate from V1 job_log for complete decoupling
 */
@Repository
interface JobLogV2Repository : JpaRepository<JobLogV2, Long> {
  fun findByJobType(jobType: String): List<JobLogV2>

  @Query(
    """
    SELECT j FROM JobLogV2 j
    WHERE j.jobType = :jobType
    ORDER BY j.createdAt DESC
    """,
  )
  fun findLatestByJobType(jobType: String): List<JobLogV2>
}

enum class LogEntryType {
  OFFENDER_SETUP_COMPLETE,
  OFFENDER_DEACTIVATED,
  OFFENDER_CHECKIN_NOT_SUBMITTED,
  OFFENDER_CHECKIN_REVIEW_SUBMITTED,
  OFFENDER_CHECKIN_ANNOTATED,
  OFFENDER_CHECKIN_RESCHEDULED,
  OFFENDER_CHECKIN_OUTSIDE_ACCESS,
}

/**
 * Repository for V2 offender event Log
 * Separate from V1 offender_event_log for complete decoupling
 */
@Repository
interface OffenderEventLogV2Repository : JpaRepository<OffenderEventLogV2, Long> {
  @Query(
    """
    select 
        e.uuid as uuid,
        e.comment as notes,
        e.createdAt as createdAt,
        e.logEntryType as logEntryType,
        e.practitioner as practitioner,
        c.uuid as checkin
    from OffenderEventLogV2 e
    left join OffenderCheckinV2 c on e.checkin = c.id
    where e.checkin is not null and c = :checkin and e.logEntryType in :ofType
    order by e.createdAt desc
  """,
  )
  fun findAllCheckinEvents(checkin: OffenderCheckinV2, ofType: Set<LogEntryType>): List<IOffenderCheckinLogEntryV2Dto>

  @Query(
    """
          select 
        e.uuid as uuid,
        e.comment as notes,
        e.createdAt as createdAt,
        e.logEntryType as logEntryType,
        e.practitioner as practitioner,
        c.uuid as checkin
    from OffenderEventLogV2 e
    left join OffenderCheckinV2 c on e.checkin = c.id
    where e.uuid = :uuid and e.checkin is not null 
    """,
  )
  fun findCheckinLogByUuid(uuid: UUID): Optional<IOffenderCheckinLogEntryV2Dto>
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
interface TotalFeedbackMonthlyRepository : JpaRepository<TotalFeedbackMonthly, LocalDate> {

  /**
   * @param fromMonth inclusive
   * @param toMonth exclusive
   */
  @Query(
    """
    select f from TotalFeedbackMonthly f
    where f.month >= :fromMonth and f.month < :toMonth
    order by f.month asc
  """,
  )
  fun findBetween(fromMonth: LocalDate, toMonth: LocalDate): List<TotalFeedbackMonthly>
}

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
          activeUsers = rs.getLong("active_users"),
          inactiveUsers = rs.getLong("inactive_users"),
          completedCheckins = rs.getLong("completed_checkins"),
          notCompletedOnTime = rs.getLong("expired_checkins"),
          avgHoursToComplete = rs.getBigDecimal("avg_hours_to_complete").toDouble(),
          avgCompletedCheckinsPerPerson = rs.getBigDecimal("avg_checkins_completed_per_person").toDouble(),
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
interface StatsSummaryProviderMonthRepository : JpaRepository<StatsSummaryProviderMonth, StatsSummaryProviderMonthId> {

  /**
   * @param fromMonth inclusive
   * @param toMonth exclusive
   */
  @Query(
    """
    select s from StatsSummaryProviderMonth s
    where s.id.rowType = 'ALL'
      and s.id.month >= :fromMonth and s.id.month < :toMonth
    order by s.id.month asc
  """,
  )
  fun findAllBetween(fromMonth: LocalDate, toMonth: LocalDate): List<StatsSummaryProviderMonth>

  /**
   * @param fromMonth inclusive
   * @param toMonth exclusive
   */
  @Query(
    """
    select s from StatsSummaryProviderMonth s
    where s.id.rowType = 'PROVIDER'
      and s.id.month >= :fromMonth and s.id.month < :toMonth
    order by s.id.providerCode asc, s.id.month asc
  """,
  )
  fun findProvidersBetween(fromMonth: LocalDate, toMonth: LocalDate): List<StatsSummaryProviderMonth>
}

@Repository
interface MigrationControlRepository : JpaRepository<MigrationControl, Long>

@Repository
interface MigrationCheckinsUuidsRepository : JpaRepository<MigrationEventsToSend, Long>

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
      "select * from get_question_templates(?::text_language, ?)",
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

  private fun questionTemplateRowMapper(rs: ResultSet, idx: Int): QuestionTemplateDto {
    val spec: Map<String, Any> = asMap(rs, "response_spec")
    return QuestionTemplateDto(
      id = rs.getLong("question_id"),
      policy = QuestionPolicy.fromString(rs.getString("policy")),
      template = rs.getString("question_template"),
      responseFormat = QuestionResponseFormat.fromString(rs.getString("response_format")),
      responseSpec = spec,
      example = rs.getString("example"),
    )
  }

  private fun listItemRowMapper(rs: ResultSet, idx: Int): QuestionListItemDto {
    val template = QuestionTemplateDto(
      id = rs.getLong("question_id"),
      policy = QuestionPolicy.fromString(rs.getString("policy")),
      template = rs.getString("question_template"),
      responseFormat = QuestionResponseFormat.fromString(rs.getString("response_format")),
      responseSpec = asMap(rs, "response_spec"),
      example = rs.getString("example"),
    )
    return QuestionListItemDto(
      template = template,
      params = asMap(rs, "params"),
    )
  }

  private fun asMap(rs: ResultSet, columnName: String): Map<String, Any> {
    val content = rs.getString(columnName) ?: return emptyMap()
    return objectMapper.readValue(
      content,
      object : TypeReference<Map<String, Any>>() {},
    )
  }
}

@Repository
interface QuestionListAssignmentRepository : JpaRepository<QuestionListAssignment, Long> {

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

  @Query(
    """
    select qla.question_list_id from question_list_assignment qla
    where qla.offender_id = :offenderId and qla.checkin_id is null
  """,
    nativeQuery = true,
  )
  fun upcomingAssignment(offenderId: Long): Long?

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
