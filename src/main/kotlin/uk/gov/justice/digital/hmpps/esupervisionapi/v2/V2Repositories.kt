package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
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
    """
    SELECT o FROM OffenderV2 o
    WHERE o.status = 'VERIFIED'
      AND o.firstCheckin IS NOT NULL
      AND o.checkinInterval IS NOT NULL
      AND NOT EXISTS (
        SELECT 1 FROM OffenderCheckinV2 c
        WHERE c.offender = o
          AND :lowerBoundInclusive <= c.dueDate
          AND c.dueDate < :upperBoundExclusive
          AND c.status IN ('CREATED', 'SUBMITTED', 'REVIEWED')
      )
    """,
  )
  fun findEligibleForCheckinCreation(
    lowerBoundInclusive: LocalDate,
    upperBoundExclusive: LocalDate,
  ): Stream<OffenderV2>

  @Query("SELECT o.crn FROM OffenderV2 o WHERE o IN :offenders")
  fun getCrnsForOffenders(offenders: List<OffenderV2>): List<String>
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
      AND c.dueDate < :expiryDate
    """,
  )
  fun findEligibleForExpiry(expiryDate: LocalDate): Stream<OffenderCheckinV2>

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
}

/**
 * Repository for Notification Configuration
 */
@Repository
interface NotificationConfigRepository : JpaRepository<NotificationConfig, Long> {
  fun findByEventType(eventType: String): Optional<NotificationConfig>
  fun findAllByOrderByEventTypeAsc(): List<NotificationConfig>
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
    WHERE a.crn = :crn
    ORDER BY a.occurredAt
    """,
  )
  fun findByCrnOrderByOccurredAt(crn: String): List<EventAuditV2>

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
