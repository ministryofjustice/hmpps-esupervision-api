package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.jobs.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import java.time.Instant
import java.util.UUID

/**
 * Generic notification entity for tracking delivery status of various messages.
 * Currently used for offender-specific messages but modelled for extension.
 */
@Entity
@Table(
  name = "generic_notification",
  indexes = [
    Index(name = "idx_generic_notification_notification_id", columnList = "notification_id", unique = true),
    Index(name = "idx_generic_notification_reference", columnList = "reference"),
    Index(name = "idx_generic_notification_created_at", columnList = "created_at"),
    Index(name = "idx_generic_notification_message_type", columnList = "message_type"),
  ],
)
open class GenericNotification(
  @Column("notification_id", unique = true, nullable = false)
  open var notificationId: UUID,

  @Column(name = "message_type", nullable = false)
  open var messageType: String,

  /**
   * Identifier for a single-shot notification. For notifications sent via a job, see `job`.
   */
  @Column(nullable = false)
  open var reference: String,

  /**
   * If the notification was sent as a part of scheduled job,
   * this column will reference that job's log entry.
   */
  @ManyToOne()
  @JoinColumn(name = "job_log", nullable = true)
  open var job: JobLog? = null,

  /**
   * Set to one of the relevant statuses obtained from GOV.UK Notify
   */
  @Column
  open var status: String? = null,

  @Column(name = "created_at")
  @CreationTimestamp
  open var createdAt: Instant? = null,

  @Column(name = "updated_at")
  @UpdateTimestamp
  open var updatedAt: Instant? = null,
) : AEntity()

@Repository
interface GenericNotificationRepository : org.springframework.data.jpa.repository.JpaRepository<GenericNotification, Long> {

  @Query(
    """
    SELECT gn FROM GenericNotification gn WHERE gn.reference = :#{#ref.reference()}
    AND gn.status IN (:statuses)
    AND gn.createdAt >= :lowerBound
    """,
  )
  fun findByJobAndStatus(ref: Referencable, statuses: List<String>, lowerBound: Instant): List<GenericNotification>

  @Query(
    """
    UPDATE GenericNotification gn
    SET gn.status = :status
    WHERE gn.notificationId IN :notificationIds
    """,
  )
  @Modifying
  fun updateNotificationStatuses(notificationIds: Collection<UUID>, status: String)

  // Find notifications created after the given instant
  fun findByCreatedAtAfter(createdAt: Instant): List<GenericNotification>
}
