package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
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
) {

  @Transactional
  fun findCheckin(uuid: UUID): OffenderCheckin? = checkinRepository.findByUuid(uuid).orElse(null)

  @Transactional
  fun checkinCreation(checkin: OffenderCheckin, event: PartialCheckinCreatedEvent): CheckinCreatedEvent? {
    require(checkin.status == CheckinStatus.CREATED)
    requireOnlyInsertedFieldsSet(checkin)
    val id = checkinRepository.maybeInsert(checkin)
    if (id != null) {
      val finalisedEvent = event.finalise(checkin, id)
      eventAuditService.recordCheckinCreated(checkin, finalisedEvent)

      appEventPublisher.publishEvent(finalisedEvent)
      return finalisedEvent
    }
    return null
  }

  /**
   * [OffenderCheckinRepository.maybeInsert] writes only uuid, offender, status, due date, created at and
   * created by. Anything else set on a new check-in would be silently dropped, so refuse it: extend
   * the INSERT before setting another field at creation time.
   */
  private fun requireOnlyInsertedFieldsSet(checkin: OffenderCheckin) {
    val unsupported = buildList {
      if (checkin.id != 0L) add("id")
      if (checkin.surveyResponse != null) add("surveyResponse")
      if (checkin.submittedAt != null) add("submittedAt")
      if (checkin.reviewStartedAt != null) add("reviewStartedAt")
      if (checkin.reviewStartedBy != null) add("reviewStartedBy")
      if (checkin.reviewedAt != null) add("reviewedAt")
      if (checkin.reviewedBy != null) add("reviewedBy")
      if (checkin.checkinStartedAt != null) add("checkinStartedAt")
      if (checkin.autoIdCheck != null) add("autoIdCheck")
      if (checkin.autoIdCheckScore != null) add("autoIdCheckScore")
      if (checkin.livenessResult != null) add("livenessResult")
      if (checkin.livenessConfidence != null) add("livenessConfidence")
      if (checkin.livenessEnabled) add("livenessEnabled")
      if (checkin.manualIdCheck != null) add("manualIdCheck")
      if (checkin.riskFeedback != null) add("riskFeedback")
      if (checkin.sensitive) add("sensitive")
      if (checkin.imageDeletedAt != null) add("imageDeletedAt")
      if (checkin.linkStatus != null) add("linkStatus")
      if (checkin.linkStatusUpdatedAt != null) add("linkStatusUpdatedAt")
    }
    require(unsupported.isEmpty()) { "Check-in creation does not persist $unsupported; extend OffenderCheckinRepository.maybeInsert" }
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
}
