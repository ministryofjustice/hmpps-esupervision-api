package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.time.Clock
import java.util.UUID

/**
 * Handles composite operations related to checkins. It's meant to be a convenience layer for the [CheckinService]
 * and should not be used anywhere else.
 *
 * The way to use this service is to pass all required data in a state that's ready to be persisted - don't put any logic here
 * (apart from calls to .finalise() on certain events).
 */
@Service
class CheckinPersistenceService(
  private val checkinRepository: OffenderCheckinRepository,
  private val offenderRepository: OffenderRepository,
  private val eventAuditService: EventAuditService,
  private val appEventPublisher: ApplicationEventPublisher,
  private val offenderEventLogRepository: OffenderEventLogRepository,
  private val clock: Clock,
  private val checkinDeliusEventService: CheckinDeliusEventService,
) {

  @Transactional
  fun findCheckin(uuid: UUID): OffenderCheckin? = checkinRepository.findByUuid(uuid).orElse(null)

  /**
   * @return false if the offender's check-ins were stopped after the caller read the offender, so
   *   nothing was created
   */
  @Transactional
  fun checkinCreation(checkin: OffenderCheckin, event: PartialCheckinCreatedEvent): Boolean {
    // The caller (e.g. the creation job) read the offender before this transaction, and their
    // check-ins may have been stopped since. Locking the offender also makes stopping check-ins
    // wait until this commits, then cancel the new check-in along with the others.
    if (offenderRepository.lockAndGetStatus(checkin.offender.id) != OffenderStatus.VERIFIED.name) {
      LOGGER.info("Not creating checkin {}: the offender's check-ins have been stopped", checkin.uuid)
      return false
    }
    val savedCheckin = checkinRepository.saveAndFlush(checkin)
    val finalisedEvent = event.finalise(savedCheckin)
    eventAuditService.recordCheckinCreated(savedCheckin, finalisedEvent)

    appEventPublisher.publishEvent(finalisedEvent)
    return true
  }

  @Transactional
  fun checkinSubmission(checkin: OffenderCheckin, event: CheckinSubmittedEvent) {
    checkinRepository.save(checkin)
    eventAuditService.recordCheckinSubmitted(checkin, event)
    if (checkinDeliusEventService.enabled()) {
      checkinDeliusEventService.recordCheckinUpdate(DomainEventType.V2_CHECKIN_SUBMITTED, checkin)
    }

    appEventPublisher.publishEvent(event)
  }

  @Transactional
  fun checkinReview(checkin: OffenderCheckin, event: CheckinReviewedEvent, reviewInfo: CheckinReviewInfo) {
    require(event.checkin.reviewedBy != null)
    checkinRepository.save(checkin)
    eventAuditService.recordCheckinReviewed(checkin, event)
    if (checkinDeliusEventService.enabled()) {
      checkinDeliusEventService.recordCheckinUpdate(DomainEventType.V2_CHECKIN_REVIEWED, checkin)
    }
    offenderEventLogRepository.save(
      OffenderEventLog(
        comment = reviewInfo.comment,
        sensitive = checkin.sensitive,
        createdAt = clock.instant(),
        logEntryType = reviewInfo.logEntryType,
        practitioner = event.checkin.reviewedBy,
        uuid = UUID.randomUUID(),
        checkin = checkin.id,
        offender = checkin.offender,
      ),
    )

    appEventPublisher.publishEvent(event)
  }

  @Transactional
  fun checkinAnnotation(checkin: OffenderCheckin, event: PartialCheckinAnnotatedEvent, request: AnnotateCheckinRequest) {
    checkinRepository.save(checkin)
    val logEntry = offenderEventLogRepository.saveAndFlush(
      OffenderEventLog(
        comment = request.notes,
        sensitive = checkin.sensitive,
        createdAt = clock.instant(),
        logEntryType = LogEntryType.OFFENDER_CHECKIN_ANNOTATED,
        practitioner = request.updatedBy,
        uuid = UUID.randomUUID(),
        offender = checkin.offender,
        checkin = checkin.id,
      ),
    )
    if (checkinDeliusEventService.enabled()) {
      checkinDeliusEventService.recordCheckinUpdate(DomainEventType.V2_CHECKIN_ANNOTATED, checkin, detailId = logEntry.uuid)
    }

    appEventPublisher.publishEvent(event.finalise(logEntry))
  }

  companion object {
    private val LOGGER = logger<CheckinPersistenceService>()
  }
}
