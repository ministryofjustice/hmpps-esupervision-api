package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import java.time.Instant
import java.util.UUID

@Entity
@Table(
  name = "offender_checkin_notification",
  indexes = [
    Index(name = "idx_checkin_notification_reference", columnList = "reference"),
  ],
)
open class CheckinNotification(
  @Column("notification_id", unique = true, nullable = false)
  open var notificationId: UUID,

  @Column(nullable = false)
  open var checkin: UUID,

  @Column(nullable = false)
  open var reference: UUID,

  @Column
  open var status: String? = null,

  @Column
  @CreationTimestamp
  open var createdAt: Instant? = null,

  @Column
  @UpdateTimestamp
  open var updatedAt: Instant? = null,
) : AEntity()

@Repository
interface CheckinNotificationRepository : org.springframework.data.jpa.repository.JpaRepository<CheckinNotification, Long>
