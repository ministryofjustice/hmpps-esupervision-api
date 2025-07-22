package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.ResourceLocator
import java.time.Instant
import java.time.ZonedDateTime
import java.util.Optional
import java.util.UUID

@Entity
@Table(name = "offender_checkin")
open class OffenderCheckin(
  @Column(unique = true, nullable = false)
  open var uuid: UUID,

  @ManyToOne(cascade = [CascadeType.DETACH])
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

  @Column("survey_response", nullable = true)
  @JdbcTypeCode(SqlTypes.JSON)
  open var surveyResponse: Map<String, Object>?,

  /**
   * Will hold the latest status and/or error of any sent notifications.
   */
  @Column("notifications", nullable = true)
  @JdbcTypeCode(SqlTypes.JSON)
  open var notifications: NotificationResults?,

  @Column("due_date")
  open var dueDate: ZonedDateTime,

  @Column("id_check_auto", nullable = true)
  @Enumerated(EnumType.STRING)
  open var autoIdCheck: AutomatedIdVerificationResult?,

  @Column("id_check_manual", nullable = true)
  @Enumerated(EnumType.STRING)
  open var manualIdCheck: ManualIdVerificationResult?,
) : AEntity() {
  fun dto(resourceLocator: ResourceLocator): OffenderCheckinDto = OffenderCheckinDto(
    uuid = uuid,
    status = status,
    dueDate = dueDate,
    offender = offender.dto(resourceLocator), // TODO: don't return whole dto, just the uuid
    submittedOn = submittedAt,
    surveyResponse = surveyResponse,
    reviewedBy = reviewedBy?.uuid,
    createdBy = createdBy.uuid,
    createdAt = createdAt,
    videoUrl = resourceLocator.getCheckinVideo(this),
    autoIdCheck = autoIdCheck,
    manualIdCheck = manualIdCheck,
    notifications = notifications,
  )

  companion object {}
}

@Repository
interface OffenderCheckinRepository : org.springframework.data.jpa.repository.JpaRepository<OffenderCheckin, Long> {
  fun findByUuid(uuid: UUID): Optional<OffenderCheckin>
  fun findByOffender(offender: Offender): Optional<OffenderCheckin>

  // returns checkins created by a practitioner with the given uuid
  fun findAllByCreatedByUuid(practitionerUuid: String, pageable: Pageable): Page<OffenderCheckin>
}
