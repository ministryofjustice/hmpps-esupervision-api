package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import java.time.Instant
import java.util.UUID

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
}

@Entity
@Table(name = "job_log")
open class JobLog(
  @Column(name = "reference", nullable = false, unique = true)
  open var reference: UUID,

  @Enumerated(EnumType.STRING)
  @Column(name = "job_type", nullable = false)
  open var jobType: JobType,

  @Column(name = "created_at", nullable = false)
  open var createdAt: Instant,

  @Column(name = "ended_at", nullable = true)
  open var endedAt: Instant? = null,
) : AEntity() {
  fun dto() = JobLogDto(reference = reference, jobType = jobType, createdAt = createdAt, endedAt = endedAt)
}
