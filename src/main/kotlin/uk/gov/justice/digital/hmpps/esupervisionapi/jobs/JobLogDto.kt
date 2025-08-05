package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import java.time.Instant
import java.util.UUID

data class JobLogDto(
  val reference: UUID,
  val jobType: JobType,
  val createdAt: Instant,
  val endedAt: Instant? = null,
)
