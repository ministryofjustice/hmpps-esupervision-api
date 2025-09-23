package uk.gov.justice.digital.hmpps.esupervisionapi.offender

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
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Referencable
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import java.time.Instant
import java.util.UUID

typealias CheckiNotificationReference = String

/**
 * Used to track status of notifications sent to the `Offender`.
 */
@Entity
@Table(
  name = "offender_checkin_notification",
  indexes = [
    Index(name = "idx_checkin_notification_notification_id", columnList = "notification_id", unique = true),
    Index(name = "idx_checkin_notification_reference", columnList = "reference"),
    Index(name = "idx_chckin_notification_created_at", columnList = "created_at"),
  ],
)
open class CheckinNotification(
  @Column("notification_id", unique = true, nullable = false)
  open var notificationId: UUID,

  @Column(nullable = false)
  open var checkin: UUID,

  /**
   * Should be used for single-shot notifications (e.g., notification not sent
   * via a scheduled job). For notification sent from a job, see `job` property.
   */
  @Column(nullable = false)
  open var reference: CheckiNotificationReference,

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
interface CheckinNotificationRepository : org.springframework.data.jpa.repository.JpaRepository<CheckinNotification, Long> {

  @Query(
    """
    SELECT cn FROM CheckinNotification cn WHERE cn.reference = :#{#ref.reference}
    AND cn.status IN (:statuses)
    AND cn.createdAt >= :lowerBound
    """,
  )
  fun findByJobAndStatus(ref: Referencable, statuses: List<String>, lowerBound: Instant): List<CheckinNotification>

  @Query(
    """
    UPDATE CheckinNotification cn 
    SET cn.status = :status 
    WHERE cn.notificationId IN :notificationIds
    """,
  )
  @Modifying
  fun updateNotificationStatuses(notificationIds: Collection<UUID>, status: String)
}
