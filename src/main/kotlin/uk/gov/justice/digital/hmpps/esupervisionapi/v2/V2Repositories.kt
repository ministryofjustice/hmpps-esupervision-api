package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
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
  fun findAllByCrn(crn: String): List<EventAuditV2>
  fun findAllByEventType(eventType: String): List<EventAuditV2>
  fun findAllByPractitionerId(practitionerId: String): List<EventAuditV2>
  fun findAllByCheckinUuid(checkinUuid: UUID): List<EventAuditV2>

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
 * Repository for Stats
 */
@Repository
interface StatsSummaryRepository : JpaRepository<StatsSummary, Int> {
  fun findBySingleton(singleton: Int = 1): StatsSummary?
}

@Repository
interface MigrationControlRepository : JpaRepository<MigrationControl, Long>

@Repository
interface MigrationCheckinsUuidsRepository : JpaRepository<MigrationEventsToSend, Long>
