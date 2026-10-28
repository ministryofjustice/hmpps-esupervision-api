package uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.persistence.V2BaseEntity
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** The event types published through [CheckinDeliusEvent] rows. */
val CHECKIN_DELIUS_EVENT_TYPES = setOf(
  DomainEventType.V2_CHECKIN_SCHEDULED,
  DomainEventType.V2_CHECKIN_RESCHEDULED,
  DomainEventType.V2_CHECKIN_CANCELLED,
  DomainEventType.V2_CHECKIN_LINK_DELIVERED,
  DomainEventType.V2_CHECKIN_LINK_NOT_DELIVERED,
)

/**
 * A check-in lifecycle event for NDelius, written in the same transaction as the change it
 * describes and published later by [CheckinDeliusEventRelayJob]. It is also the source of the
 * event detail NDelius fetches, so the detail reflects the check-in as it was at the time.
 */
@Entity
@Table(name = "checkin_delius_event_v2")
open class CheckinDeliusEvent(
  @Column("event_id", nullable = false)
  open var eventId: UUID,

  @Column("event_type", nullable = false)
  @Enumerated(EnumType.STRING)
  open var eventType: DomainEventType,

  @Column("checkin_uuid", nullable = false)
  open var checkinUuid: UUID,

  @Column("crn", nullable = false)
  open var crn: String,

  @Column("event_number")
  open var eventNumber: Long?,

  /** The check-in date this event refers to: set for scheduled and rescheduled events only. */
  @Column("date_time")
  open var dateTime: Instant?,

  @Column("notes", nullable = false)
  open var notes: String,

  @Column("occurred_at", nullable = false)
  open var occurredAt: Instant,

  @Column("published_at")
  open var publishedAt: Instant? = null,

  @Column("publish_attempts", nullable = false)
  open var publishAttempts: Int = 0,

  @Column("last_error")
  open var lastError: String? = null,
) : V2BaseEntity()

@Repository
interface CheckinDeliusEventRepository : JpaRepository<CheckinDeliusEvent, Long> {

  fun findByEventId(eventId: UUID): Optional<CheckinDeliusEvent>

  fun findAllByCheckinUuidOrderById(checkinUuid: UUID): List<CheckinDeliusEvent>

  /**
   * Insert an event unless the check-in already has one of that type (rescheduling excepted).
   * @return 1 if inserted, 0 if it was a duplicate
   */
  @Query(
    """
    insert into checkin_delius_event_v2 (event_id, event_type, checkin_uuid, crn, event_number, date_time, notes, occurred_at)
    values (:#{#e.eventId}, :#{#e.eventType.name()}, :#{#e.checkinUuid}, :#{#e.crn}, :#{#e.eventNumber}, :#{#e.dateTime}, :#{#e.notes}, :#{#e.occurredAt})
    on conflict (checkin_uuid, event_type) where event_type <> 'V2_CHECKIN_RESCHEDULED' do nothing
    """,
    nativeQuery = true,
  )
  @Modifying
  fun insertUnlessDuplicate(e: CheckinDeliusEvent): Int

  /**
   * Unpublished events that are next in line for their check-in: every earlier event for the
   * check-in has been published, and none of them was published after [publishedBefore].
   */
  @Query(
    """
    select e.* from checkin_delius_event_v2 e
    where e.published_at is null
      and not exists (
        select 1 from checkin_delius_event_v2 earlier
        where earlier.checkin_uuid = e.checkin_uuid
          and earlier.id < e.id
          and earlier.published_at is null
      )
      and not exists (
        select 1 from checkin_delius_event_v2 recent
        where recent.checkin_uuid = e.checkin_uuid
          and recent.published_at > :publishedBefore
      )
    order by e.id
    limit :limit
    """,
    nativeQuery = true,
  )
  fun findPublishable(publishedBefore: Instant, limit: Int): List<CheckinDeliusEvent>

  @Query("update checkin_delius_event_v2 set published_at = :publishedAt, publish_attempts = publish_attempts + 1, last_error = null where id = :id", nativeQuery = true)
  @Modifying
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun markPublished(id: Long, publishedAt: Instant): Int

  @Query("update checkin_delius_event_v2 set publish_attempts = publish_attempts + 1, last_error = :error where id = :id", nativeQuery = true)
  @Modifying
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun markFailed(id: Long, error: String): Int
}
