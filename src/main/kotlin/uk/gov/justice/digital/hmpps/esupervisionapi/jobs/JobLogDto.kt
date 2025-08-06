package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import java.time.Instant

data class JobLogDto(
  val reference: String,
  val jobType: JobType,
  val createdAt: Instant,
  val endedAt: Instant? = null,
)
