package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.ResourceLocator
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import java.util.stream.Stream

@Entity
@Table(
  name = "offender_checkin",
  indexes = [
    Index(columnList = "due_date", name = "checkin_due_date_idx", unique = false),
    Index(columnList = "created_at", name = "checkin_created_at_idx", unique = false),
    Index(columnList = "status", name = "checkin_status_idx", unique = false),
    Index(columnList = "offender_id", name = "offender_checkin_offender_idx", unique = false),
    Index(columnList = "created_by", name = "offender_checkin_created_by_idx", unique = false),
  ],
)
open class OffenderCheckin(
  @Column(unique = true, nullable = false)
  open var uuid: UUID,

  @ManyToOne(cascade = [CascadeType.DETACH])
  @JoinColumn("offender_id", referencedColumnName = "id", nullable = false)
  open var offender: Offender,

  @Column("submitted_at", nullable = true)
  open var submittedAt: Instant?,

  @Column("reviewed_at", nullable = true)
  open var reviewedAt: Instant?,

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

  @Column("due_date")
  open var dueDate: LocalDate,

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
    submittedAt = submittedAt,
    surveyResponse = surveyResponse,
    reviewedBy = reviewedBy?.uuid,
    reviewedAt = reviewedAt,
    createdBy = createdBy.uuid,
    createdAt = createdAt,
    videoUrl = resourceLocator.getCheckinVideo(this),
    snapshotUrl = resourceLocator.getCheckinSnapshot(this),
    autoIdCheck = autoIdCheck,
    manualIdCheck = manualIdCheck,
  )

  companion object {}
}

@Repository
interface OffenderCheckinRepository : org.springframework.data.jpa.repository.JpaRepository<OffenderCheckin, Long> {
  fun findByUuid(uuid: UUID): Optional<OffenderCheckin>

  // returns checkins created by a practitioner with the given uuid
  @EntityGraph(attributePaths = ["offender", "createdBy", "reviewedBy"], type = EntityGraph.EntityGraphType.LOAD)
  fun findAllByCreatedByUuid(practitionerUuid: String, pageable: Pageable): Page<OffenderCheckin>

  @EntityGraph(attributePaths = ["offender.practitioner"])
  @Query(
    """
    SELECT c FROM OffenderCheckin c  
    WHERE c.dueDate < :cutoff
    AND c.createdAt >= :lowerBound
    AND c.status = 'CREATED'
 """,
  )
  fun findAllAboutToExpire(cutoff: LocalDate, lowerBound: Instant): Stream<OffenderCheckin>

  /**
   * @param cutoff day on which submitting a checkin is no longer allowed
   * @param lowerBound only consider checkins created on lowerBound or after
   */
  @Query(
    """
    UPDATE OffenderCheckin c 
    SET c.status = 'EXPIRED' 
    WHERE c.dueDate < :cutoff
    AND c.createdAt >= :lowerBound
    AND c.status = 'CREATED'
  """,
  )
  @Modifying
  fun updateStatusToExpired(cutoff: LocalDate, lowerBound: Instant): Int

  /**
   * To be used when we want to cancel all outstanding checkins for an offender
   */
  @Query(
    """
    UPDATE OffenderCheckin c SET c.status = 'CANCELLED'
    WHERE c.offender = :offender AND c.status IN ('CREATED', 'SUBMITTED')""",
  )
  @Modifying
  fun updateStatusToCancelled(offender: Offender): Int
}
