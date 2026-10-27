package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationService
import java.time.Clock
import java.time.Duration
import kotlin.math.min

/** One-off batch to backfill existing verified offenders' selected Delius event. */
@Component
class CurrentEventBackfillJob(
  private val clock: Clock,
  private val offenderRepository: OffenderRepository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val offenderDeactivationService: OffenderDeactivationService,
  private val jobLogRepository: JobLogRepository,
  @param:Value("\${app.scheduling.current-event-backfill.chunk-size:100}") private val chunkSize: Int,
  @param:Value("\${app.scheduling.current-event-backfill.dry-run:true}") private val dryRun: Boolean,
) {
  fun process() {
    val pageSize = min(chunkSize, INdiliusApiClient.MAX_BATCH_SIZE)
    require(pageSize > 0) { "Current event backfill chunk size must be greater than zero" }

    val startedAt = clock.instant()
    val jobLog = jobLogRepository.saveAndFlush(JobLog(jobType = JOB_TYPE, createdAt = startedAt))
    var pages = 0
    var processed = 0
    var plannedSelections = 0
    var plannedDeactivations = 0
    var failed = 0
    var lastOffenderId = 0L

    LOGGER.info("Current event backfill started: dryRun={}, chunkSize={}", dryRun, pageSize)
    try {
      while (true) {
        val offenders = offenderRepository.findVerifiedWithoutCurrentEventAfterId(
          OffenderStatus.VERIFIED,
          lastOffenderId,
          PageRequest.of(0, pageSize),
        )
        if (offenders.isEmpty()) break

        lastOffenderId = offenders.last().id
        pages += 1
        val detailsByCrn = try {
          ndiliusApiClient.getContactDetailsForMultiple(offenders.map { it.crn }).associateBy { it.crn }
        } catch (e: Exception) {
          LOGGER.warn("Could not fetch NDelius contact details for current event backfill page {}", pages, e)
          failed += offenders.size
          continue
        }

        for (offender in offenders) {
          val details = detailsByCrn[offender.crn]
          if (details == null) {
            LOGGER.warn("No NDelius contact details returned for current event backfill CRN {}", offender.crn)
            failed += 1
            continue
          }

          processed += 1
          try {
            val sentenceEvents = details.events.filter { it.sentence != null }
            if (sentenceEvents.isEmpty()) {
              backfillWithoutActiveSentence(offender, details)
              plannedDeactivations++
              continue
            }

            if (sentenceEvents.size > 1 && sentenceEvents.any { it.sentence?.expectedEndDate == null }) {
              LOGGER.warn("Cannot determine longest active sentence for CRN {} because an expected end date is missing", offender.crn)
              failed += 1
              continue
            }

            val selectedEvent = if (sentenceEvents.size == 1) {
              sentenceEvents.single()
            } else {
              sentenceEvents.maxWithOrNull(
                compareBy<Event> { requireNotNull(it.sentence?.expectedEndDate) }.thenBy { it.number },
              )!!
            }
            backfillEvent(offender, selectedEvent)
            plannedSelections++
          } catch (e: Exception) {
            LOGGER.warn("Failed current event backfill for CRN {} on page {}", offender.crn, pages, e)
            failed += 1
          }
        }
      }
    } finally {
      jobLog.endedAt = clock.instant()
      jobLogRepository.saveAndFlush(jobLog)
      LOGGER.info(
        "Current event backfill completed: dryRun={}, processed={}, plannedSelections={}, plannedDeactivations={}, failed={}, pages={}, took={}",
        dryRun,
        processed,
        plannedSelections,
        plannedDeactivations,
        failed,
        pages,
        Duration.between(startedAt, jobLog.endedAt),
      )
    }

    check(failed == 0) { "Current event backfill failed for $failed offenders; see the job logs before retrying." }
  }

  private fun backfillEvent(offender: Offender, event: Event) {
    if (dryRun) {
      LOGGER.info(
        "DRY RUN: would set current_event={} for CRN {} (expectedEndDate={})",
        event.number,
        offender.crn,
        event.sentence?.expectedEndDate,
      )
    } else {
      offender.currentEvent = event.number
      offender.updatedAt = clock.instant()
      offenderRepository.save(offender)
      LOGGER.info("Set current_event={} for CRN {}", event.number, offender.crn)
    }
  }

  private fun backfillWithoutActiveSentence(offender: Offender, details: ContactDetails) {
    if (dryRun) {
      LOGGER.info("DRY RUN: would deactivate CRN {} because Delius returned no active sentence", offender.crn)
    } else {
      offenderDeactivationService.deactivateOffender(
        offender = offender,
        reason = "Automatically deactivated: no active sentence in NDelius",
        contactDetails = details,
        auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS,
      )
      LOGGER.info("Deactivated CRN {} because Delius returned no active sentence", offender.crn)
    }
  }

  companion object {
    const val JOB_TYPE = "V2_CURRENT_EVENT_BACKFILL"
    private val LOGGER = LoggerFactory.getLogger(CurrentEventBackfillJob::class.java)
  }
}
