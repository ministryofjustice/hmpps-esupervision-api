package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import java.time.Clock

/**
 * Drains the checkin_note_resend work list, resending check-in answers to NDelius (ESUP-1956).
 *
 * Deliberately NOT @Transactional: the annotation row must be committed before the domain event
 * is published (see [CheckinNoteResendService]).
 */
@Component
class CheckinNoteResendJob(
  private val clock: Clock,
  private val checkinNoteResendService: CheckinNoteResendService,
  private val jobLogRepository: JobLogRepository,
  @Value("\${app.scheduling.checkin-note-resend.batch-size}") private val batchSize: Int,
  @Value("\${app.scheduling.checkin-note-resend.events-per-second}") private val eventsPerSecond: Double,
) {
  @Scheduled(cron = "\${app.scheduling.checkin-note-resend.cron}")
  @SchedulerLock(
    name = "Checkin Note Resend Job",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT30M",
  )
  fun process() {
    val startedAt = clock.instant()

    var processed = 0
    var failed = false
    try {
      processed = checkinNoteResendService.processPending(batchSize, eventsPerSecond)
    } catch (e: Exception) {
      failed = true
      LOGGER.error("Checkin Note Resend Job failed", e)
    }

    // the work list is usually empty; don't log a job run for every no-op tick
    if (processed > 0 || failed) {
      jobLogRepository.saveAndFlush(JobLog(jobType = "CHECKIN_NOTE_RESEND", createdAt = startedAt, endedAt = clock.instant()))
      LOGGER.info("Checkin Note Resend Job completed, processed {} rows", processed)
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(CheckinNoteResendJob::class.java)
  }
}
