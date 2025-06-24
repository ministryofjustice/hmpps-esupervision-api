package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.springframework.stereotype.Repository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.Pagination
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Entity
@Table(name = "offender_checkin")
open class OffenderCheckin(
  @Column(unique = true, nullable = false)
  open var uuid: UUID,

  @ManyToOne
  @JoinColumn("offender_id", referencedColumnName = "id", nullable = false)
  open var offender: Offender,

  @Column("submitted_at", nullable = true)
  open var submittedAt: Instant?,

  @Column("created_at", nullable = false)
  open var createdAt: Instant,

  @ManyToOne
  @JoinColumn("reviewed_by", referencedColumnName = "id", nullable = true)
  open var reviewedBy: Practitioner?,

  @ManyToOne
  @JoinColumn("created_by", referencedColumnName = "id", nullable = false)
  open var createdBy: Practitioner,

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  open var status: CheckinStatus,

  open var questions: String,

  open var answers: String?,

  @Column("due_date")
  open var dueDate: Instant,

  @Column("id_check_auto", nullable = true)
  @Enumerated(EnumType.STRING)
  open var autoIdCheck: AutomatedIdVerificationResult?,

  @Column("id_check_manual", nullable = true)
  @Enumerated(EnumType.STRING)
  open var manualIdCheck: ManualIdVerificationResult?,
) : AEntity() {
  fun dto(): OffenderCheckinDto = OffenderCheckinDto(
    uuid = uuid,
    status = status,
    dueDate = dueDate,
    offender = offender.dto(), // TODO: don't return whole dto, just the uuid
    submittedOn = submittedAt,
    questions = questions,
    answers = answers,
    reviewedBy = reviewedBy?.uuid,
    createdBy = createdBy.uuid,
    createdAt = createdAt,
    videoUrl = null,
    autoIdCheck = autoIdCheck,
    manualIdCheck = manualIdCheck,
  )
}

@Repository
interface OffenderCheckinRepository : org.springframework.data.jpa.repository.JpaRepository<OffenderCheckin, Long> {
  fun findByUuid(uuid: UUID): Optional<OffenderCheckin>
  fun findByOffender(offender: Offender): Optional<OffenderCheckin>

  // returns checkins created by a practitioner with the given uuid
  fun findAllByCreatedByUuid(practitionerUuid: UUID, pageable: Pageable): Page<OffenderCheckin>
}
