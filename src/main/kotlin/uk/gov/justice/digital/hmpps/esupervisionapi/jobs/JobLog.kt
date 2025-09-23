package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Referencable
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import java.time.Instant

enum class JobType {
  /**
   * Runs daily and finds candidates to send checkin invites to. See
   *
   *  @see CheckinNotifier
   */
  CHECKIN_NOTIFICATIONS_JOB,

  /**
   * Runs daily, updates notification status of sent checkin invites
   * by fetching the appropriate status from GOV.UK Notify
   */
  CHECKIN_NOTIFICATION_STATUS_UPDATE_JOB,

  /**
   * Runs daily, sends notification to the practitioner about checkins that
   * expired (e.g., the offender failed to submit a checkin in the agreed time window).
   */
  CHECKIN_EXIPIRED_NOTIFICATIONS_JOB,
}

@Entity
@Table(
  name = "job_log",
  indexes = [
    Index(name = "idx_job_log_job_type", columnList = "job_type"),
    Index(name = "idx_job_log_created_at", columnList = "created_at"),
  ],
)
open class JobLog(
  @Enumerated(EnumType.STRING)
  @Column(name = "job_type", nullable = false)
  open var jobType: JobType,

  @Column(name = "created_at", nullable = false)
  open var createdAt: Instant,

  @Column(name = "ended_at", nullable = true)
  open var endedAt: Instant? = null,
) : AEntity(),
  Referencable {
  fun dto() = JobLogDto(reference = reference, jobType = jobType, createdAt = createdAt, endedAt = endedAt)

  /**
   * An identifier that can be used with external systems.
   *
   * Note: Should be treated as an opaque value.
   */
  @Transient
  override val reference = "BLK-${String.format("%05d", id)}"
}
