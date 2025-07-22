package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "shedlock")
open class Schedlock(
  @Id
  @Column(name = "name", length = 64, nullable = false)
  open var name: String,

  @Column(name = "lock_until", nullable = true)
  open var lockUntil: Instant? = null,

  @Column(name = "locked_at", nullable = true)
  open var lockedAt: Instant? = null,

  @Column(name = "locked_by", length = 255, nullable = true)
  open var lockedBy: String? = null,
)
