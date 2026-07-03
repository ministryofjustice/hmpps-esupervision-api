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
    val logEntry = jobLogRepository.saveAndFlush(JobLog(jobType = "CHECKIN_NOTE_RESEND", createdAt = clock.instant()))
    LOGGER.info("Checkin Note Resend Job(id={}) started", logEntry.id)

    try {
      checkinNoteResendService.processPending(batchSize, eventsPerSecond)
    } catch (e: Exception) {
      LOGGER.error("Checkin Note Resend Job(id={}) failed", logEntry.id, e)
    }

    logEntry.endedAt = clock.instant()
    jobLogRepository.saveAndFlush(logEntry)
    LOGGER.info("Checkin Note Resend Job(id={}) completed", logEntry.id)
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(CheckinNoteResendJob::class.java)
  }
}
