package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import java.util.UUID

enum class LogEntryType {
  OFFENDER_DEACTIVATED,
}

/**
 * Log of notable events during the offender's time on the platform.
 * Practitioners are required to provide reason/comment for
 * certain events (e.g., terminating an offender account).
 */
@Entity
@Table(name = "offender_event_log")
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

  @Column("created_at", updatable = false)
  @CreationTimestamp
  open var createdAt: java.time.Instant? = null,
) : AEntity() {
  fun dto(): OffenderEventLogDto {
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
  fun findAllByOffenderUuid(offenderUuid: UUID, pageable: Pageable): Page<OffenderEventLog>
}
