package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import org.springframework.stereotype.Repository
import java.lang.Long
import java.time.Instant

@Repository
interface JobLogRepository : org.springframework.data.jpa.repository.JpaRepository<JobLog, Long> {
  fun findByCreatedAtGreaterThanAndJobType(createdAt: Instant, jobType: JobType): List<JobLog>
}
