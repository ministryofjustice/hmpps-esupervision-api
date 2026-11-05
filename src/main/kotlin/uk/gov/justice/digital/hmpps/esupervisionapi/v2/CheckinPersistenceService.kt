package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.UpcomingCheckinService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.activeEventNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventNotes
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.time.Clock
import java.time.Instant
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
  private val upcomingCheckinService: UpcomingCheckinService,
  private val entityManager: EntityManager,
  private val appConfig: AppConfig,
  private val questionListAssignmentRepository: QuestionListAssignmentRepository,
) {

  @Transactional
  fun findCheckin(uuid: UUID): OffenderCheckin? = checkinRepository.findByUuid(uuid).orElse(null)

  /**
   * Creates [checkin], or, if it has an id, activates it: an upcoming (SCHEDULED) check-in the
   * caller read and made CREATED.
   *
   * @return null if nothing was created: its offender already has a CREATED check-in for the due
   *   date, or the upcoming check-in was cancelled (check-ins were stopped) or moved after the
   *   caller read it
   */
  @Transactional
  fun checkinCreation(checkin: OffenderCheckin, event: PartialCheckinCreatedEvent): CheckinCreatedEvent? {
    require(checkin.status == CheckinStatus.CREATED)
    if (checkin.id == 0L && upcomingCheckinService.enabled()) {
      // Locking the offender serialises this with scheduling, which may have scheduled this date
      // after the caller looked for an upcoming check-in: activate that one rather than creating a
      // second check-in NDelius would also record. It also loads the offender in this transaction,
      // as recording the check-in reads it and the caller's may be detached (the creation job's).
      checkin.offender = offenderRepository.findByIdForUpdate(checkin.offender.id).orElseThrow()
      val scheduled = checkinRepository.findScheduledForOffenders(listOf(checkin.offender.id), checkin.dueDate).singleOrNull()
      if (scheduled != null) {
        LOGGER.info("Activating checkin {}, scheduled after the caller looked for it, instead of creating checkin {}", scheduled.uuid, checkin.uuid)
        scheduled.status = CheckinStatus.CREATED
        scheduled.createdAt = checkin.createdAt
        scheduled.createdBy = checkin.createdBy
        return checkinCreation(scheduled, event.copy(checkin = event.checkin.copy(uuid = scheduled.uuid)))
      }
      // A check-in for this date whose invite failed was cancelled, so it doesn't stop the insert
      // below, but it takes the date: don't create another and send a second invite. Failing an
      // invite takes the same offender lock, so this sees one that failed after the caller looked.
      if (checkinRepository.existsLinkFailed(checkin.offender.id, checkin.dueDate)) {
        LOGGER.info("Not creating checkin {}: the invite for the check-in due {} failed", checkin.uuid, checkin.dueDate)
        return null
      }
    }
    val activating = checkin.id != 0L
    if (upcomingCheckinService.enabled()) {
      // the invite is sent once this commits; the link-sent gate holds submission until then
      checkin.linkStatus = CheckinLinkStatus.SENDING
      checkin.linkStatusUpdatedAt = clock.instant()
    }
    val id = (if (activating) activate(checkin) else insert(checkin)) ?: return null
    // an activated check-in was recorded in NDelius when it was scheduled
    if (!activating && upcomingCheckinService.enabled()) {
      // the event's number is the one on record, which can be unset when the caller has NDelius's
      val eventNumber = event.currentEvent ?: event.checkin.personalDetails?.let { activeEventNumber(checkin.offender, it, appConfig.esup2305DeliusEventSelection) }
      checkinDeliusEventService.record(DomainEventType.V2_CHECKIN_SCHEDULED, checkin, CheckinDeliusEventNotes.scheduled(checkin.dueDate), eventNumber, checkin.dueDate)
    }
    val finalisedEvent = event.finalise(checkin, id)
    eventAuditService.recordCheckinCreated(checkin, finalisedEvent)

    appEventPublisher.publishEvent(finalisedEvent)
    return finalisedEvent
  }

  /** @return the new check-in's id, or null if a CREATED check-in already exists for its offender and due date */
  private fun insert(checkin: OffenderCheckin): Long? {
    requireOnlyInsertedFieldsSet(checkin)
    return checkinRepository.maybeInsert(checkin)
  }

  /**
   * [checkin] is an upcoming check-in the caller read and activated.
   * @return its id, or null if it can't be activated
   */
  private fun activate(checkin: OffenderCheckin): Long? {
    // The caller read the upcoming check-in before this transaction, and stopping check-ins may
    // have cancelled it since, or a schedule change moved it to another date. Saving the stale copy
    // would undo that. Locking it also makes stopping check-ins, or moving it, wait until this
    // commits, then find it created.
    // A check-in created for its date meanwhile would make activating it break the one-per-day index.
    if (checkinRepository.lockAndGetScheduledDueDate(checkin.id) != checkin.dueDate ||
      checkinRepository.existsCreated(checkin.offender.id, checkin.dueDate)
    ) {
      LOGGER.info("Not activating checkin {}: it is no longer scheduled for {}, or that date already has a check-in", checkin.uuid, checkin.dueDate)
      // With open-in-view the caller may have activated a managed copy, which committing this
      // transaction would write back over the cancellation or move.
      if (entityManager.contains(checkin)) entityManager.refresh(checkin)
      return null
    }
    val id = checkinRepository.saveAndFlush(checkin).id
    // the link columns are only written on insert (see OffenderCheckin.linkStatus)
    checkin.linkStatusUpdatedAt?.let { checkinRepository.markLinkSending(id, it) }
    return id
  }

  /**
   * [OffenderCheckinRepository.maybeInsert] writes only uuid, offender, status, due date, created at,
   * created by and link status. Anything else set on a new check-in would be silently dropped, so refuse it: extend
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
      if (checkin.cancelledAt != null) add("cancelledAt")
      if (checkin.cancelledBy != null) add("cancelledBy")
    }
    require(unsupported.isEmpty()) { "Check-in creation does not persist $unsupported; extend OffenderCheckinRepository.maybeInsert" }
  }

  /**
   * Saves an open check-in the offender is working through (identity, face match, liveness), if
   * it is still open in the database.
   *
   * @throws ResponseStatusException 409 if it is no longer CREATED: it changed after the caller
   *   read it, for example a practitioner cancelled it or it expired
   */
  @Transactional
  fun saveOpenCheckin(checkin: OffenderCheckin) {
    requireStillOpen(checkin)
    checkinRepository.save(checkin)
  }

  /**
   * The caller read [checkin] before this transaction, often before slow calls to NDelius or
   * Rekognition. Saving the stale copy would overwrite a cancellation committed since. Locking also
   * makes a concurrent cancellation wait until the caller's transaction commits.
   */
  private fun requireStillOpen(checkin: OffenderCheckin) {
    if (checkinRepository.lockAndGetStatus(checkin.id) != CheckinStatus.CREATED.name) {
      LOGGER.info("Not saving checkin {}: it is no longer {}", checkin.uuid, CheckinStatus.CREATED)
      // With open-in-view the caller's copy is managed: don't leave its changes to be flushed later.
      if (entityManager.contains(checkin)) entityManager.refresh(checkin)
      throw ResponseStatusException(HttpStatus.CONFLICT, "Checkin ${checkin.uuid} is no longer open")
    }
  }

  /**
   * @throws ResponseStatusException 409 if the check-in is no longer CREATED in the database: it
   *   changed (for example a practitioner cancelled it) after the caller read it
   */
  @Transactional
  fun checkinSubmission(checkin: OffenderCheckin, event: CheckinSubmittedEvent) {
    requireStillOpen(checkin)
    checkinRepository.save(checkin)
    eventAuditService.recordCheckinSubmitted(checkin, event)
    upcomingCheckinService.scheduleNext(checkin.offender.id, event.checkin.personalDetails?.let { activeEventNumber(checkin.offender, it, appConfig.esup2305DeliusEventSelection) })
    if (checkinDeliusEventService.enabled()) {
      checkinDeliusEventService.recordCheckinUpdate(
        DomainEventType.V2_CHECKIN_SUBMITTED,
        checkin,
        eventNumber = event.currentEvent,
      )
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

  /**
   * Cancels [checkin], which the caller read with status [expectedStatus], recording the reason in
   * the event log and, if NDelius has the check-in, a cancelled event for NDelius. Only the
   * cancellation columns are written, so a change the caller's copy missed (link status, ID
   * check) isn't overwritten.
   *
   * @param toEvent builds the event from the cancelled check-in
   * @return the event, or null if nothing was saved: the check-in's status changed after the
   *   caller read it (for example the offender submitted it, or the creation job activated it)
   */
  @Transactional
  fun checkinCancellation(
    checkin: OffenderCheckin,
    expectedStatus: CheckinStatus,
    cancellation: CheckinCancellation,
    toEvent: (OffenderCheckin) -> CheckinCancelledEvent,
  ): CheckinCancelledEvent? {
    // The conditional update locks the row, so a concurrent submission, activation or stop waits
    // until this commits, then sees it cancelled.
    val updated = checkinRepository.cancel(checkin.id, expectedStatus, cancellation.cancelledAt, cancellation.cancelledBy, cancellation.sensitive)
    if (updated == 0) {
      LOGGER.info("Not cancelling checkin {}: it is no longer {}", checkin.uuid, expectedStatus)
      return null
    }
    // An upcoming check-in's custom questions are the offender's pending assignment (an open one's
    // are already attached to it). Attach them to the cancelled check-in, so they aren't asked at
    // the next check-in instead.
    if (expectedStatus == CheckinStatus.SCHEDULED) {
      questionListAssignmentRepository.bindUpcomingAssignment(checkin.offender.id, checkin.id)
    }
    // With open-in-view the caller's copy is managed: refresh it so it shows the cancellation.
    val cancelled = if (entityManager.contains(checkin)) checkin.also { entityManager.refresh(it) } else checkinRepository.findById(checkin.id).get()
    offenderEventLogRepository.save(
      OffenderEventLog(
        comment = cancellation.reason,
        sensitive = cancelled.sensitive,
        createdAt = clock.instant(),
        logEntryType = LogEntryType.OFFENDER_CHECKIN_CANCELLED,
        practitioner = cancellation.cancelledBy,
        uuid = UUID.randomUUID(),
        offender = cancelled.offender,
        checkin = cancelled.id,
      ),
    )
    if (checkinDeliusEventService.enabled()) {
      checkinDeliusEventService.recordCancelledIfRecorded(cancelled, cancellation.reason)
    }
    val event = toEvent(cancelled)
    eventAuditService.recordCheckinCancelled(cancelled, event)
    // Nothing is upcoming now: schedule the next check-in. The cancelled one holds its date, so
    // this is the one after it.
    upcomingCheckinService.scheduleNext(cancelled.offender.id, event.checkin.personalDetails?.let { activeEventNumber(cancelled.offender, it, appConfig.esup2305DeliusEventSelection) })

    appEventPublisher.publishEvent(event)
    return event
  }

  companion object {
    private val LOGGER = logger<CheckinPersistenceService>()
  }
}

/** A practitioner's cancellation of a check-in, ready to persist */
data class CheckinCancellation(
  val cancelledBy: ExternalUserId,
  val cancelledAt: Instant,
  val reason: String,
  val sensitive: Boolean,
)
