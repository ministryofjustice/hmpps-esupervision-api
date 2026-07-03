package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import com.google.common.util.concurrent.RateLimiter
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.config.SurveyValueExpansionsConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.formatHumanReadableDateTime
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinNoteResend
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinNoteResendRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.DomainEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.LogEntryType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.appendQuestionsAndAnswers
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.time.Clock
import java.util.UUID

/**
 * Resends check-in question answers to NDelius as corrective contact notes (ESUP-1956).
 *
 * A bug (fixed in 8eefb1a) published checkin-submitted events before the transaction committed,
 * so NDelius could fetch the note before survey_response was visible and the answers were omitted.
 * For each affected check-in (seeded manually into checkin_note_resend) this service creates an
 * annotation log entry containing the answers and publishes a checkin-annotated event, producing
 * a new contact note in NDelius via the usual detail-URL callback.
 */
@Service
class CheckinNoteResendService(
  private val resendRepository: CheckinNoteResendRepository,
  private val checkinRepository: OffenderCheckinRepository,
  private val eventLogRepository: OffenderEventLogRepository,
  private val outboxItemRepository: OutboxItemRepository,
  private val domainEventService: DomainEventService,
  private val expansionsConfig: SurveyValueExpansionsConfig,
  private val transactionTemplate: TransactionTemplate,
  private val clock: Clock,
) {

  fun processPending(batchSize: Int, eventsPerSecond: Double = 2.0): Int {
    val rows = resendRepository.findBySentAtIsNull(PageRequest.of(0, batchSize))
    if (rows.isEmpty()) {
      return 0
    }
    logger.info("Checkin note resend: {} pending rows", rows.size)
    val rateLimiter = RateLimiter.create(eventsPerSecond)

    var processed = 0
    for (row in rows) {
      try {
        processRow(row, rateLimiter)
        processed++
      } catch (e: Exception) {
        logger.error("Checkin note resend failed for checkin={}, will retry next run", row.checkin, e)
      }
    }
    logger.info("Checkin note resend: handled {}/{} rows", processed, rows.size)
    return processed
  }

  private fun processRow(row: CheckinNoteResend, rateLimiter: RateLimiter) {
    val checkin = checkinRepository.findByUuid(row.checkin).orElse(null)
    if (checkin == null) {
      logger.warn("Checkin note resend: checkin {} not found, skipping", row.checkin)
      markRow(row, "SKIPPED: checkin not found")
      return
    }
    if (checkin.surveyResponse.isNullOrEmpty()) {
      logger.warn("Checkin note resend: checkin {} has no survey response, skipping", row.checkin)
      markRow(row, "SKIPPED: no survey response")
      return
    }

    // The annotation must be committed before the event is published: NDelius fetches the note
    // text via a callback as soon as it sees the event (the same race the original bug hit).
    val logEntry = row.annotationUuid?.let { uuid ->
      eventLogRepository.findByUuid(uuid)
        .orElseThrow { IllegalStateException("annotation $uuid recorded for checkin ${row.checkin} but log entry not found") }
        .also {
          require(it.checkin == checkin.id && it.logEntryType == LogEntryType.OFFENDER_CHECKIN_ANNOTATED) {
            "annotation $uuid recorded for checkin ${row.checkin} does not match expected checkin/logEntryType"
          }
        }
    } ?: transactionTemplate.execute {
      val saved = eventLogRepository.saveAndFlush(
        OffenderEventLog(
          comment = buildNotes(checkin),
          sensitive = checkin.sensitive,
          createdAt = clock.instant(),
          logEntryType = LogEntryType.OFFENDER_CHECKIN_ANNOTATED,
          practitioner = "SYSTEM",
          uuid = UUID.randomUUID(),
          checkin = checkin.id,
          offender = checkin.offender,
        ),
      )
      row.annotationUuid = saved.uuid
      resendRepository.saveAndFlush(row)
      saved
    }!!

    rateLimiter.acquire()
    val published = domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_ANNOTATED,
      uuid = logEntry.uuid,
      crn = checkin.offender.crn,
      description = "Check-in note resend for ${checkin.offender.crn} (ESUP-1956)",
    )
    if (!published) {
      // leave sentAt null so the next run retries; annotationUuid is already persisted,
      // so the retry republishes the same annotation instead of creating a duplicate
      logger.warn("Checkin note resend: publish failed for checkin={}, leaving row pending", row.checkin)
      row.notes = "PUBLISH FAILED: will retry"
      resendRepository.saveAndFlush(row)
      return
    }

    // clear the outbox row created by the offender_event_log_v2 insert trigger
    val updated = outboxItemRepository.markAsSent(OutboxItemType.CHECKIN_ANNOTATED.name, logEntry.id)
    if (updated != 1) {
      logger.warn("Checkin note resend: expected to mark 1 outbox item as sent for annotation={}, updated={}", logEntry.uuid, updated)
    }
    markRow(row, "SENT")
  }

  private fun markRow(row: CheckinNoteResend, notes: String) {
    row.sentAt = clock.instant()
    row.notes = notes
    resendRepository.saveAndFlush(row)
  }

  internal fun buildNotes(checkin: OffenderCheckin): String {
    val submitted = checkin.submittedAt ?: checkin.createdAt
    val sb = StringBuilder()
    sb.appendLine("This note was resent due to a system issue. It contains the answers for the check in submitted on ${formatHumanReadableDateTime(submitted)}.")
    sb.appendLine()
    sb.appendQuestionsAndAnswers(checkin.surveyResponse!!, expansionsConfig)
    return sb.toString().trimEnd('\n')
  }

  companion object {
    private val logger = LoggerFactory.getLogger(CheckinNoteResendService::class.java)
  }
}
