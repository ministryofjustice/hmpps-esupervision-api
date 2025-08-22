package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import java.util.UUID

enum class LogEntryType {
  OFFENDER_DEACTIVATED,
  OFFENDER_CHECKIN_NOT_SUBMITTED,
}

/**
 * Log of notable events during the offender's time on the platform.
 * Practitioners are required to provide reason/comment for
 * certain events (e.g., terminating an offender account).
 */
@Entity
@Table(
  name = "offender_event_log",
  indexes = [
    Index(name = "offender_event_log_log_entry_type_idx", columnList = "log_entry_type", unique = false),
    Index(name = "offender_event_log_offender_idx", columnList = "offender_id", unique = false),
    Index(name = "offender_event_log_practitioner_idx", columnList = "practitioner_id", unique = false),
  ],
)
open class OffenderEventLog(
  @Column(unique = true, nullable = false)
  open var uuid: UUID,

  @Column(name = "log_entry_type", nullable = false)
  @Enumerated(EnumType.STRING)
  open var logEntryType: LogEntryType,

  @Column
  open var comment: String,

  @ManyToOne(fetch = jakarta.persistence.FetchType.EAGER)
  open var practitioner: Practitioner,

  @ManyToOne(fetch = jakarta.persistence.FetchType.EAGER)
  open var offender: Offender,

  /**
   * Note: this is set only for certain type of events, so we don't
   * want to expose it in every DTO. Caller needs to explicitly
   * ask for it.
   */
  @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY, optional = true)
  @JoinColumn(name = "checkin", referencedColumnName = "id", updatable = false)
  open var checkin: OffenderCheckin? = null,

  @Column("created_at", updatable = false)
  @CreationTimestamp
  open var createdAt: java.time.Instant? = null,
) : AEntity() {
  fun dto(): IOffenderEventLogDto {
    assert(createdAt != null, { "You should not be creating DTOs without creation timestamp" })
    return OffenderEventLogDto(
      uuid = uuid,
      logEntryType = logEntryType,
      comment = comment,
      practitioner = practitioner.uuid,
      offender = offender.uuid,
      createdAt = createdAt!!,
    )
  }
}

interface OffenderEventLogRepository : org.springframework.data.jpa.repository.JpaRepository<OffenderEventLog, Long> {

  @Query(
    """
    select
     e.uuid as uuid,
     e.logEntryType as logEntryType,
     e.comment as comment,
     e.createdAt as createdAt,
     o.uuid as offender,
     p.uuid as practitioner
    from OffenderEventLog e
    join e.offender o 
    join o.practitioner p
    where o = :offender
    order by e.createdAt desc
  """,
  )
  fun findAllByOffender(offender: Offender, pageable: Pageable): Page<IOffenderEventLogDto>

  @Query(
    """
    select
     e.uuid as uuid,
     e.logEntryType as logEntryType,
     e.comment as comment,
     e.createdAt as createdAt,
     c.uuid as checkin,
     o.uuid as offender,
     p.uuid as practitioner
    from OffenderEventLog e
    join e.offender o 
    join o.practitioner p
    left join e.checkin c
    where
        e.checkin is NOT NULL
        AND e.logEntryType in :entryTypes and e.checkin = :checkin  
    order by e.createdAt desc
  """,
  )
  fun findAllCheckinEntries(checkin: OffenderCheckin, entryTypes: Set<LogEntryType>, pageable: Pageable): Page<IOffenderCheckinEventLogDto>
}
