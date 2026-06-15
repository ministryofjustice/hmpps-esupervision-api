package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import com.google.common.util.concurrent.RateLimiter
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.DomainEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.MigrationControl
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.MigrationEventsToSend
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SetupEventBackfillRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.activeEventNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.AdditionalInformation
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.time.Clock
import java.util.UUID

@Service
class MigrationEventReplayService(
  private val checkinRepository: OffenderCheckinRepository,
  private val offenderRepository: OffenderRepository,
  private val offenderSetupRepository: OffenderSetupRepository,
  private val backfillRepository: SetupEventBackfillRepository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val domainEventService: DomainEventService,
  private val eventAuditLogRepository: EventAuditRepository,
  private val clock: Clock,
) {
  private val logger = LoggerFactory.getLogger(javaClass)

  /**
   * Replay domain events for migrated offenders
   * Call this after SQL migration is complete
   */
  fun replayOffenderEvents(crns: Map<String, MigrationControl>, batchSize: Int = 50, eventsPerSecond: Double = 10.0): Set<String> {
    logger.info("Starting offender event replay")
    val rateLimiter = RateLimiter.create(eventsPerSecond)

    val auditEvents = eventAuditLogRepository.findAllByEventType("SETUP_COMPLETED").associateBy { it.crn }

    var page = 0
    var totalProcessed = 0
    var processedCrns = mutableSetOf<String>()

    do {
      val batch = offenderRepository.findMigratedWithoutAudit(
        PageRequest.of(page, batchSize),
      )
      logger.info("page={}, batch size={}", page, batch.size)

      batch.forEach { offender ->
        if (crns[offender.crn]?.offenderEvents ?: false) return@forEach
        try {
          rateLimiter.acquire()
          domainEventService.publishDomainEvent(
            eventType = DomainEventType.V2_SETUP_COMPLETED,
            uuid = offender.uuid,
            crn = offender.crn,
            description = "[MIGRATION] completed setup for offender ${offender.crn}",
            occurredAt = auditEvents[offender.crn]?.occurredAt?.atZone(clock.zone) ?: offender.createdAt.atZone(clock.zone),
            additionalInformation = offender.currentEvent?.let { AdditionalInformation(eventNumber = it) },
          )
          processedCrns.add(offender.crn)
          crns[offender.crn]?.let { control -> control.offenderEvents = true }
          totalProcessed++
        } catch (e: Exception) {
          logger.error("Failed to publish event for offender crn={} uuid={}, page={}", offender.crn, offender.uuid, page, e)
        }
      }

      page++
      logger.info("Processed {} offenders", totalProcessed)
    } while (batch.isNotEmpty())

    logger.info("Offender event replay complete: {} events published", totalProcessed)
    return processedCrns
  }

  fun replaySelectedCheckinEvents(checkins: List<MigrationEventsToSend>, eventsPerSecond: Double = 10.0) {
    logger.info("Starting checkin event replay for {} checkins", checkins.size)
    val rateLimiter = RateLimiter.create(eventsPerSecond)

    val uuids = checkins.associateBy { it.checkin }

    val checkinsSubmitted = checkinRepository
      .findMigratedByStatus(CheckinStatus.SUBMITTED, PageRequest.of(0, 1000))
      .filter { uuids.contains(it.uuid) }
    val checkinsReviewed = checkinRepository
      .findMigratedByStatus(CheckinStatus.REVIEWED, PageRequest.of(0, 1000))
      .filter { uuids.contains(it.uuid) }
    val checkinsExpired = checkinRepository
      .findMigratedByStatus(CheckinStatus.EXPIRED, PageRequest.of(0, 1000))
      .filter { uuids.contains(it.uuid) }

    // turns out ndelius doesn't listen to "checkin-created' events, so we can skip those
    for (checkin in checkinsSubmitted + checkinsReviewed) {
      rateLimiter.acquire()
      CheckinStatus.SUBMITTED.publishEvent(checkin)
      uuids[checkin.uuid]?.let {
        it.sentAt = clock.instant()
        it.notes = "SUBMITTED"
      }
    }
    for (checkin in checkinsReviewed) {
      rateLimiter.acquire()
      CheckinStatus.REVIEWED.publishEvent(checkin)
      uuids[checkin.uuid]?.let {
        it.sentAt = clock.instant()
        it.notes += "REVIEWED "
      }
    }
    for (checkin in checkinsExpired) {
      rateLimiter.acquire()
      CheckinStatus.EXPIRED.publishEvent(checkin)
      uuids[checkin.uuid]?.let {
        it.sentAt = clock.instant()
        it.notes = "EXPIRED"
      }
    }
  }

  /**
   * Replay domain events for migrated checkins
   */
  fun replayCheckinEvents(crns: Map<String, MigrationControl>, batchSize: Int = 50, eventsPerSecond: Double = 10.0) {
    logger.info("Starting checkin event replay")
    val rateLimiter = RateLimiter.create(eventsPerSecond)

    var totalProcessed = 0

    fun sendAndMark(control: MigrationControl, status: CheckinStatus, checkin: OffenderCheckin) {
      if (!status.value(control)) {
        rateLimiter.acquire()
        status.publishEvent(checkin)
        status.setColumn(control, true)
      }
    }

    val submitted = processCheckinBatch(CheckinStatus.SUBMITTED, batchSize) { checkin ->
      val control = crns[checkin.offender.crn]
      if (control == null || control.checkinSubmitted) return@processCheckinBatch
      sendAndMark(control, CheckinStatus.CREATED, checkin)
      sendAndMark(control, CheckinStatus.SUBMITTED, checkin)
    }
    totalProcessed += submitted.size
    logger.info("Processed SUBMITTED events for CRNS: {}", submitted)

    val reviewed = processCheckinBatch(CheckinStatus.REVIEWED, batchSize) { checkin ->
      val control = crns[checkin.offender.crn]
      if (control == null || control.checkinReviewed) return@processCheckinBatch
      sendAndMark(control, CheckinStatus.CREATED, checkin)
      sendAndMark(control, CheckinStatus.SUBMITTED, checkin)
      sendAndMark(control, CheckinStatus.REVIEWED, checkin)
    }
    totalProcessed += reviewed.size
    logger.info("Processed REVIEWED events for CRNS: {}", reviewed)

    val expired = processCheckinBatch(CheckinStatus.EXPIRED, batchSize) { checkin ->
      val control = crns[checkin.offender.crn]
      if (control == null || control.checkinExpired) return@processCheckinBatch
      sendAndMark(control, CheckinStatus.CREATED, checkin)
      sendAndMark(control, CheckinStatus.EXPIRED, checkin)
      if (checkin.reviewedAt != null) {
        sendAndMark(control, CheckinStatus.REVIEWED, checkin)
      }
    }
    totalProcessed += expired.size
    logger.info("Processed EXPIRED events for CRNS: {}", expired)

    logger.info("Checkin event replay complete: {} events published", totalProcessed)
  }

  private fun processCheckinBatch(
    status: CheckinStatus,
    batchSize: Int,
    processor: (OffenderCheckin) -> Unit,
  ): Set<String> {
    var page = 0
    var processedInStatus = 0
    val processedCrns = mutableSetOf<String>()
    do {
      val batch = checkinRepository.findMigratedByStatus(status, PageRequest.of(page, batchSize))
      logger.info("page={}, size={}", page, batch.size)
      batch.forEach { checkin ->
        try {
          processor(checkin)
          processedInStatus++
          processedCrns.add(checkin.offender.crn)
        } catch (e: Exception) {
          logger.error("Failed to process checkin {}", checkin.uuid, e)
        }
      }
      page++
    } while (batch.isNotEmpty())
    return processedCrns
  }

  fun additionalInformation(checkin: OffenderCheckin) = if (checkin.offender.currentEvent == null) null else AdditionalInformation(checkin.offender.currentEvent)

  fun CheckinStatus.setColumn(control: MigrationControl, value: Boolean) {
    when (this) {
      CheckinStatus.CREATED -> control.checkinCreated = value
      CheckinStatus.SUBMITTED -> control.checkinSubmitted = value
      CheckinStatus.REVIEWED -> control.checkinReviewed = value
      CheckinStatus.EXPIRED -> control.checkinExpired = value
      else -> logger.warn("setColum: Unhandled checkin status {} for CRN {}", this, control.crn)
    }
  }
  fun CheckinStatus.value(control: MigrationControl): Boolean = when (this) {
    CheckinStatus.CREATED -> control.checkinCreated
    CheckinStatus.SUBMITTED -> control.checkinSubmitted
    CheckinStatus.REVIEWED -> control.checkinReviewed
    CheckinStatus.EXPIRED -> control.checkinExpired
    else -> false
  }
  fun CheckinStatus.publishEvent(checkin: OffenderCheckin) {
    when (this) {
      CheckinStatus.CREATED -> publishCheckinCreatedEvent(checkin)
      CheckinStatus.SUBMITTED -> publishCheckinSubmittedEvent(checkin)
      CheckinStatus.REVIEWED -> publishCheckinReviewedEvent(checkin)
      CheckinStatus.EXPIRED -> publishCheckinExpiredEvent(checkin)
      else -> logger.warn("publishEvent: Unhandled checkin status {} for CRN {}", this, checkin.offender.crn)
    }
  }

  private fun publishCheckinCreatedEvent(checkin: OffenderCheckin) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_CREATED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "[MIGRATION] Check-in created for ${checkin.offender.crn}",
      occurredAt = checkin.createdAt.atZone(clock.zone),
      additionalInformation = additionalInformation(checkin),
    )
  }

  private fun publishCheckinSubmittedEvent(checkin: OffenderCheckin) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_SUBMITTED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "[MIGRATION] Check-in submitted for ${checkin.offender.crn}",
      occurredAt = checkin.submittedAt?.atZone(clock.zone),
      additionalInformation = additionalInformation(checkin),
    )
  }

  private fun publishCheckinReviewedEvent(checkin: OffenderCheckin) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_REVIEWED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "[MIGRATION] Check-in reviewed for ${checkin.offender.crn}",
      occurredAt = checkin.reviewedAt?.atZone(clock.zone),
      additionalInformation = additionalInformation(checkin),
    )
  }

  private fun publishCheckinExpiredEvent(checkin: OffenderCheckin) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_EXPIRED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "[MIGRATION] Check-in expired for ${checkin.offender.crn}",
      occurredAt = checkin.dueDate.plusDays(3).atStartOfDay(clock.zone),
      additionalInformation = additionalInformation(checkin),
    )
  }

  /**
   * Phase 1 of the setup-completed event backfill: create missing offender_setup_v2 rows for
   * VERIFIED offenders that pre-date the V2 setup flow (e.g. V1-migrated).
   */
  fun createMissingSetupV2Rows(batchSize: Int = 50): Int {
    logger.info("Starting offender_setup_v2 backfill")
    var totalProcessed = 0
    while (true) {
      val batch = backfillRepository.findPendingSetupRowCreation(PageRequest.of(0, batchSize))
      if (batch.isEmpty()) break
      for (row in batch) {
        try {
          val offender = offenderRepository.findById(row.offenderId).orElse(null)
          if (offender == null) {
            logger.warn("Backfill row id={} references missing offender_id={}", row.id, row.offenderId)
            row.setupRowCreated = true
            continue
          }
          if (offenderSetupRepository.findByOffender(offender).isEmpty) {
            offenderSetupRepository.save(
              OffenderSetup(
                uuid = UUID.randomUUID(),
                offender = offender,
                practitionerId = offender.practitionerId,
                createdAt = offender.createdAt,
                startedAt = offender.createdAt,
                setupCounter = 1,
              ),
            )
            logger.info("Created backfilled offender_setup_v2 for offender uuid={} crn={}", offender.uuid, offender.crn)
            totalProcessed++
          }
          row.setupRowCreated = true
        } catch (e: Exception) {
          logger.error("Failed to create setup row for backfill id={} offender_id={}", row.id, row.offenderId, e)
        }
      }
      backfillRepository.saveAllAndFlush(batch)
      if (batch.size < batchSize) break
    }
    logger.info("offender_setup_v2 backfill complete: {} rows created", totalProcessed)
    return totalProcessed
  }

  /**
   * Phase 2 of the setup-completed event backfill: publish V2_SETUP_COMPLETED for VERIFIED
   * offenders with at least one active Delius event. Phase 1 must run first so every offender
   * has a setup row to provide setupId.
   */
  fun replayActiveOffenderSetupEvents(batchSize: Int = 50, eventsPerSecond: Double = 10.0): Int {
    logger.info("Starting V2_SETUP_COMPLETED backfill")
    val rateLimiter = RateLimiter.create(eventsPerSecond)
    var totalProcessed = 0
    while (true) {
      val batch = backfillRepository.findPendingEventSend(PageRequest.of(0, batchSize))
      if (batch.isEmpty()) break

      val offendersById = offenderRepository.findAllById(batch.map { it.offenderId }).associateBy { it.id }
      val crns = offendersById.values.map { it.crn }

      val contactsByCrn = try {
        ndiliusApiClient.getContactDetailsForMultiple(crns).associateBy { it.crn }
      } catch (e: Exception) {
        logger.warn("Failed to fetch contact details for batch of {} crns - aborting this run, will retry next cron tick", crns.size, e)
        break
      }

      for (row in batch) {
        val offender = offendersById[row.offenderId]
        if (offender == null) {
          logger.warn("Backfill row id={} references missing offender_id={}", row.id, row.offenderId)
          row.eventSent = true
          row.eventSentAt = clock.instant()
          continue
        }
        try {
          val details = contactsByCrn[offender.crn]
          if (details == null || details.events.isEmpty()) {
            logger.info("Skipping V2_SETUP_COMPLETED for crn={}: no active Delius events", offender.crn)
            row.eventSent = true
            row.eventSentAt = clock.instant()
            continue
          }
          val setup = offenderSetupRepository.findByOffender(offender).orElse(null)
          if (setup == null) {
            logger.warn("No offender_setup_v2 row for offender uuid={} crn={} - expected Phase 1 to have created one; skipping", offender.uuid, offender.crn)
            continue
          }
          rateLimiter.acquire()
          domainEventService.publishDomainEvent(
            eventType = DomainEventType.V2_SETUP_COMPLETED,
            uuid = offender.uuid,
            crn = offender.crn,
            description = "Practitioner completed setup for offender ${offender.crn}",
            occurredAt = offender.createdAt.atZone(clock.zone),
            additionalInformation = AdditionalInformation(
              eventNumber = activeEventNumber(offender, details),
              setupId = setup.setupId(),
            ),
          )
          row.eventSent = true
          row.eventSentAt = clock.instant()
          totalProcessed++
        } catch (e: Exception) {
          logger.error("Failed to publish V2_SETUP_COMPLETED for backfill id={} crn={}", row.id, offender.crn, e)
        }
      }
      backfillRepository.saveAllAndFlush(batch)
      if (batch.size < batchSize) break
    }
    logger.info("V2_SETUP_COMPLETED backfill complete: {} events published", totalProcessed)
    return totalProcessed
  }
}
