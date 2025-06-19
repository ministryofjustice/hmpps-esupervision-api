package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Entity
@Table(name = "offender_checkin")
open class OffenderCheckin(
  @Column(unique = true, nullable = false)
  open var uuid: UUID,

  @OneToOne()
  @JoinColumn("offender_id", referencedColumnName = "id", nullable = false)
  open var offender: Offender,

  @Column("submitted_on", nullable = false)
  open var submittedOn: Instant,

  @Column("reviewed_by")
  @OneToOne()
  @JoinColumn("practitioner_id", referencedColumnName = "id", nullable = true)
  open var reviewedBy: Practitioner?,

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  open var status: CheckinStatus,

  open var questions: String,

  open var answers: String?,
) : AEntity() {
  fun dto(): OffenderCheckinDto = OffenderCheckinDto(
    uuid = uuid,
    status = status,
    offender = offender.dto(),
    submittedOn = submittedOn,
    questions = questions,
    answers = answers,
    reviewedBy = reviewedBy?.uuid,
    videoUrl = null,
  )
}

@Repository
interface OffenderCheckinRepository {
  fun findByUuid(uuid: UUID): Optional<OffenderCheckin>
  fun findByOffender(offender: Offender): Optional<OffenderCheckin>
}
