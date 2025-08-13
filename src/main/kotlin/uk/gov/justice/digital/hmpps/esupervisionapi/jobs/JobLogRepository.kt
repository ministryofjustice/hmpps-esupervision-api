package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.lang.Long
import java.time.Instant

@Repository
interface JobLogRepository : org.springframework.data.jpa.repository.JpaRepository<JobLog, Long> {
  @Query(
    """
    SELECT j FROM JobLog j 
    WHERE j.createdAt >= :createdAt AND j.jobType = :jobType AND j.endedAt IS NOT NULL 
    ORDER BY j.createdAt ASC
  """,
  )
  fun findByCreatedAtGreaterThanAndJobType(createdAt: Instant, jobType: JobType): List<JobLog>
}
