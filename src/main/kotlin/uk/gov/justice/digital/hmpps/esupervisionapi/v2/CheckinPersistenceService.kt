package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.UpcomingCheckinService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.activeEventNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventNotes
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventService
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
  private val eventAuditService: EventAuditService,
  private val appEventPublisher: ApplicationEventPublisher,
  private val offenderEventLogRepository: OffenderEventLogRepository,
  private val clock: Clock,
  private val checkinDeliusEventService: CheckinDeliusEventService,
  private val upcomingCheckinService: UpcomingCheckinService,
  private val entityManager: EntityManager,
) {

  @Transactional
  fun findCheckin(uuid: UUID): OffenderCheckin? = checkinRepository.findByUuid(uuid).orElse(null)

  /**
   * @return false if [checkin] activates an upcoming check-in that was cancelled after the caller
   *   read it (check-ins were stopped), so nothing was created
   */
  @Transactional
  fun checkinCreation(checkin: OffenderCheckin, event: PartialCheckinCreatedEvent): Boolean {
    // Activating: the caller read the upcoming check-in before this transaction, and stopping
    // check-ins may have cancelled it since. Saving the stale copy would undo the cancellation.
    // Locking it also makes stopping check-ins wait until this commits, then cancel it as created.
    if (checkin.id != 0L && checkinRepository.lockAndGetStatus(checkin.id) != CheckinStatus.SCHEDULED.name) {
      LOGGER.info("Not activating checkin {}: it is no longer scheduled", checkin.uuid)
      // With open-in-view the caller may have activated a managed copy, which committing this
      // transaction would write back over the cancellation.
      if (entityManager.contains(checkin)) entityManager.refresh(checkin)
      return false
    }
    // an activated check-in was recorded in NDelius when it was scheduled
    val notScheduledBefore = checkin.status == CheckinStatus.CREATED && checkin.id == 0L
    if (upcomingCheckinService.enabled()) {
      // the invite is sent once this commits; the link-sent gate holds submission until then
      checkin.linkStatus = CheckinLinkStatus.SENDING
      checkin.linkStatusUpdatedAt = clock.instant()
    }
    val savedCheckin = checkinRepository.saveAndFlush(checkin)
    if (!notScheduledBefore && upcomingCheckinService.enabled()) {
      // the link columns are only written on insert (see OffenderCheckin.linkStatus)
      checkinRepository.markLinkSending(savedCheckin.id, savedCheckin.linkStatusUpdatedAt!!)
    }
    if (notScheduledBefore && upcomingCheckinService.enabled()) {
      // the event's number is the one on record, which can be unset when the caller has NDelius's
      val eventNumber = event.currentEvent ?: event.checkin.personalDetails?.let { activeEventNumber(savedCheckin.offender, it) }
      checkinDeliusEventService.record(DomainEventType.V2_CHECKIN_SCHEDULED, savedCheckin, CheckinDeliusEventNotes.scheduled(savedCheckin.dueDate), eventNumber, savedCheckin.dueDate)
    }
    val finalisedEvent = event.finalise(savedCheckin)
    eventAuditService.recordCheckinCreated(savedCheckin, finalisedEvent)

    appEventPublisher.publishEvent(finalisedEvent)
    return true
  }

  @Transactional
  fun checkinSubmission(checkin: OffenderCheckin, event: CheckinSubmittedEvent) {
    checkinRepository.save(checkin)
    eventAuditService.recordCheckinSubmitted(checkin, event)
    upcomingCheckinService.scheduleNext(checkin.offender.id, event.checkin.personalDetails?.let { activeEventNumber(checkin.offender, it) })
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
