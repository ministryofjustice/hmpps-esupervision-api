package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import org.springframework.stereotype.Repository
import java.lang.Long

@Repository
interface JobLogRepository : org.springframework.data.jpa.repository.JpaRepository<JobLog, Long>
