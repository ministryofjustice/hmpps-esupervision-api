package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.UpcomingCheckinService
import java.time.Clock

/**
 * ESUP-2223: schedules the next check-in for everyone set up before the feature was turned on, so
 * NDelius shows their upcoming check-in. Covers verified offenders with neither an upcoming nor an
 * open check-in: one with an open check-in has the next scheduled when it's submitted or missed.
 * Safe to run again: anyone it has scheduled is skipped.
 *
 * Runs as a one-shot K8s CronJob pod (see docs/HELM-AND-K8s-CRON-JOBS), kept suspended and run by
 * hand when the feature is turned on. It doesn't run alongside the check-in creation job, which may
 * be creating the same check-ins: it stops if that job is running or starts.
 */
@Component
class UpcomingCheckinBackfillJob(
  private val clock: Clock,
  private val offenderRepository: OffenderRepository,
  private val upcomingCheckinService: UpcomingCheckinService,
  private val jobLogRepository: JobLogRepository,
) {
  fun process() {
    if (!upcomingCheckinService.enabled()) {
      LOGGER.warn("Upcoming Check-in Backfill Job not run: feature ESUP_2223 is off")
      return
    }
    val start = clock.instant()
    if (jobLogRepository.existsByJobTypeAndCreatedAtGreaterThanEqualAndEndedAtIsNull(CheckinCreationJob.JOB_TYPE, clock.today().atStartOfDay(clock.zone).toInstant())) {
      LOGGER.warn("Upcoming Check-in Backfill Job not run: today's check-in creation job is running (or didn't finish)")
      return
    }
    val logEntry = jobLogRepository.saveAndFlush(JobLog(jobType = JOB_TYPE, createdAt = start))
    LOGGER.info("Upcoming Check-in Backfill Job(id={}) started", logEntry.id)

    var processed = 0
    var lastId = 0L
    do {
      if (jobLogRepository.existsByJobTypeAndCreatedAtGreaterThanEqual(CheckinCreationJob.JOB_TYPE, start)) {
        LOGGER.warn("Upcoming Check-in Backfill Job(id={}) stopped after {} offenders: the check-in creation job started", logEntry.id, processed)
        break
      }
      val ids = offenderRepository.findIdsWithoutUpcomingCheckin(lastId, CHUNK_SIZE)
      // Failures are logged by scheduleNext, and the offender is picked up again on the next run.
      ids.forEach { upcomingCheckinService.scheduleNext(it) }
      processed += ids.size
      lastId = ids.lastOrNull() ?: lastId
    } while (ids.size == CHUNK_SIZE)

    logEntry.endedAt = clock.instant()
    jobLogRepository.saveAndFlush(logEntry)
    LOGGER.info("Upcoming Check-in Backfill Job(id={}) completed: processed={}", logEntry.id, processed)
  }

  companion object {
    const val JOB_TYPE = "V2_UPCOMING_CHECKIN_BACKFILL"
    private const val CHUNK_SIZE = 100
    private val LOGGER = LoggerFactory.getLogger(UpcomingCheckinBackfillJob::class.java)
  }
}
