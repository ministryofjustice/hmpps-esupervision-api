package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import java.time.Clock

/**
 * Runs as a one-shot K8s CronJob pod (see docs/HELM-AND-K8s-CRON-JOBS), not @Scheduled —
 * single-run safety comes from the CronJob's concurrencyPolicy: Forbid, dispatched via
 * BatchManager based on BATCH_TYPE=OFFENDER_ELIGIBILITY_SYNC.
 */
@Component
class OffenderEligibilitySyncJob(
  private val clock: Clock,
  private val jobLogRepository: JobLogRepository,
) {
  fun process() {
    val logEntry = jobLogRepository.saveAndFlush(JobLog(jobType = JOB_TYPE, createdAt = clock.instant()))
    LOGGER.info("Offender Eligibility Sync Job(id={}) started", logEntry.id)

    // TODO(ESUP-2082): sync offender eligibility state

    logEntry.endedAt = clock.instant()
    jobLogRepository.saveAndFlush(logEntry)
    LOGGER.info("Offender Eligibility Sync Job(id={}) completed", logEntry.id)
  }

  companion object {
    const val JOB_TYPE = "V2_OFFENDER_ELIGIBILITY_SYNC"
    private val LOGGER = LoggerFactory.getLogger(OffenderEligibilitySyncJob::class.java)
  }
}
