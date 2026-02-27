package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import com.google.common.util.concurrent.RateLimiter
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.DomainEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.MigrationControl
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.MigrationEventsToSend
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.AdditionalInformation
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.time.Clock

@Service
class MigrationEventReplayService(
  private val checkinRepository: OffenderCheckinV2Repository,
  private val offenderRepository: OffenderV2Repository,
  private val domainEventService: DomainEventService,
  private val eventAuditLogRepository: EventAuditV2Repository,
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
      .findMigratedByStatus(CheckinV2Status.SUBMITTED, PageRequest.of(0, 1000))
      .filter { uuids.contains(it.uuid) }
    val checkinsReviewed = checkinRepository
      .findMigratedByStatus(CheckinV2Status.REVIEWED, PageRequest.of(0, 1000))
      .filter { uuids.contains(it.uuid) }
    val checkinsExpired = checkinRepository
      .findMigratedByStatus(CheckinV2Status.EXPIRED, PageRequest.of(0, 1000))
      .filter { uuids.contains(it.uuid) }

    // turns out ndelius doesn't listen to "checkin-created' events, so we can skip those
    for (checkin in checkinsSubmitted + checkinsReviewed) {
      rateLimiter.acquire()
      CheckinV2Status.SUBMITTED.publishEvent(checkin)
      uuids[checkin.uuid]?.let {
        it.sentAt = clock.instant()
        it.notes = "SUBMITTED"
      }
    }
    for (checkin in checkinsReviewed) {
      rateLimiter.acquire()
      CheckinV2Status.REVIEWED.publishEvent(checkin)
      uuids[checkin.uuid]?.let {
        it.sentAt = clock.instant()
        it.notes += "REVIEWED "
      }
    }
    for (checkin in checkinsExpired) {
      rateLimiter.acquire()
      CheckinV2Status.EXPIRED.publishEvent(checkin)
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

    fun sendAndMark(control: MigrationControl, status: CheckinV2Status, checkin: OffenderCheckinV2) {
      if (!status.value(control)) {
        rateLimiter.acquire()
        status.publishEvent(checkin)
        status.setColumn(control, true)
      }
    }

    val submitted = processCheckinBatch(CheckinV2Status.SUBMITTED, batchSize) { checkin ->
      val control = crns[checkin.offender.crn]
      if (control == null || control.checkinSubmitted) return@processCheckinBatch
      sendAndMark(control, CheckinV2Status.CREATED, checkin)
      sendAndMark(control, CheckinV2Status.SUBMITTED, checkin)
    }
    totalProcessed += submitted.size
    logger.info("Processed SUBMITTED events for CRNS: {}", submitted)

    val reviewed = processCheckinBatch(CheckinV2Status.REVIEWED, batchSize) { checkin ->
      val control = crns[checkin.offender.crn]
      if (control == null || control.checkinReviewed) return@processCheckinBatch
      sendAndMark(control, CheckinV2Status.CREATED, checkin)
      sendAndMark(control, CheckinV2Status.SUBMITTED, checkin)
      sendAndMark(control, CheckinV2Status.REVIEWED, checkin)
    }
    totalProcessed += reviewed.size
    logger.info("Processed REVIEWED events for CRNS: {}", reviewed)

    val expired = processCheckinBatch(CheckinV2Status.EXPIRED, batchSize) { checkin ->
      val control = crns[checkin.offender.crn]
      if (control == null || control.checkinExpired) return@processCheckinBatch
      sendAndMark(control, CheckinV2Status.CREATED, checkin)
      sendAndMark(control, CheckinV2Status.EXPIRED, checkin)
      if (checkin.reviewedAt != null) {
        sendAndMark(control, CheckinV2Status.REVIEWED, checkin)
      }
    }
    totalProcessed += expired.size
    logger.info("Processed EXPIRED events for CRNS: {}", expired)

    logger.info("Checkin event replay complete: {} events published", totalProcessed)
  }

  private fun processCheckinBatch(
    status: CheckinV2Status,
    batchSize: Int,
    processor: (OffenderCheckinV2) -> Unit,
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

  fun CheckinV2Status.setColumn(control: MigrationControl, value: Boolean) {
    when (this) {
      CheckinV2Status.CREATED -> control.checkinCreated = value
      CheckinV2Status.SUBMITTED -> control.checkinSubmitted = value
      CheckinV2Status.REVIEWED -> control.checkinReviewed = value
      CheckinV2Status.EXPIRED -> control.checkinExpired = value
      else -> logger.warn("setColum: Unhandled checkin status {} for CRN {}", this, control.crn)
    }
  }
  fun CheckinV2Status.value(control: MigrationControl): Boolean = when (this) {
    CheckinV2Status.CREATED -> control.checkinCreated
    CheckinV2Status.SUBMITTED -> control.checkinSubmitted
    CheckinV2Status.REVIEWED -> control.checkinReviewed
    CheckinV2Status.EXPIRED -> control.checkinExpired
    else -> false
  }
  fun CheckinV2Status.publishEvent(checkin: OffenderCheckinV2) {
    when (this) {
      CheckinV2Status.CREATED -> publishCheckinCreatedEvent(checkin)
      CheckinV2Status.SUBMITTED -> publishCheckinSubmittedEvent(checkin)
      CheckinV2Status.REVIEWED -> publishCheckinReviewedEvent(checkin)
      CheckinV2Status.EXPIRED -> publishCheckinExpiredEvent(checkin)
      else -> logger.warn("publishEvent: Unhandled checkin status {} for CRN {}", this, checkin.offender.crn)
    }
  }

  private fun publishCheckinCreatedEvent(checkin: OffenderCheckinV2) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_CREATED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "[MIGRATION] Check-in created for ${checkin.offender.crn}",
      occurredAt = checkin.createdAt.atZone(clock.zone),
    )
  }

  private fun publishCheckinSubmittedEvent(checkin: OffenderCheckinV2) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_SUBMITTED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "[MIGRATION] Check-in submitted for ${checkin.offender.crn}",
      occurredAt = checkin.submittedAt?.atZone(clock.zone),
    )
  }

  private fun publishCheckinReviewedEvent(checkin: OffenderCheckinV2) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_REVIEWED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "[MIGRATION] Check-in reviewed for ${checkin.offender.crn}",
      occurredAt = checkin.reviewedAt?.atZone(clock.zone),
    )
  }

  private fun publishCheckinExpiredEvent(checkin: OffenderCheckinV2) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_EXPIRED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "[MIGRATION] Check-in expired for ${checkin.offender.crn}",
      occurredAt = checkin.dueDate.plusDays(3).atStartOfDay(clock.zone),
    )
  }
}
