package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventService

@Service
class OffenderPersistenceService(
  private val offenderRepository: OffenderRepository,
  private val offenderSetupRepository: OffenderSetupRepository,
  private val checkinRepository: OffenderCheckinRepository,
  private val outboxItemRepository: OutboxItemRepository,
  private val questionListAssignmentRepository: QuestionListAssignmentRepository,
  private val eventAuditService: EventAuditService,
  private val entityManager: EntityManager,
  private val appEventPublisher: ApplicationEventPublisher,
  private val checkinDeliusEventService: CheckinDeliusEventService,
) {

  /**
   * Deactivates the offender only if it is still VERIFIED with the event selection the caller evaluated
   * ([OffenderDeactivatedEvent.expectedCurrentEvent]) and the same latest setup ([OffenderDeactivatedEvent.setup]).
   * Otherwise the decision is stale (e.g. the offender was stopped and reactivated meanwhile) and nothing happens.
   *
   * The event is persisted and published as given, including `offender.updatedAt` as the deactivation time,
   * so build it with [uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationService].
   *
   * @return true if the offender was deactivated
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun offenderDeactivation(event: OffenderDeactivatedEvent): Boolean {
    if (offenderRepository.deactivateIfUnchanged(event.offenderId, event.expectedCurrentEvent, event.setup?.primaryKey, event.offender.updatedAt) == 0) {
      LOGGER.info("Skipping stale deactivation for CRN {}", event.offender.crn)
      return false
    }

    event.setup?.let {
      outboxItemRepository.addOutboxItem(OutboxItemType.OFFENDER_DEACTIVATED, it.primaryKey)
    }
    // In the order activating an upcoming check-in (SCHEDULED -> CREATED) locks rows: the check-in,
    // then (by trigger) the upcoming question assignment, so the two wait rather than deadlock.
    // SCHEDULED first: if an activation holds the row, this waits for it and then skips it, and the
    // CREATED update, a later statement, sees it as created and cancels it.
    val cancelledIds = checkinRepository.cancelForOffender(event.offenderId, CheckinStatus.SCHEDULED.name) +
      checkinRepository.cancelForOffender(event.offenderId, CheckinStatus.CREATED.name)
    // ESUP-2223: NDelius has the upcoming check-in, and the open one, so it's told they're cancelled:
    // those this cancelled, not those read before (one may have been submitted since).
    if (checkinDeliusEventService.enabled()) {
      checkinRepository.findAllById(cancelledIds).forEach { checkinDeliusEventService.recordCancelledIfRecorded(it, CHECKINS_STOPPED_REASON) }
    }
    val cancelledCheckins = cancelledIds.size
    val deletedAssignments = questionListAssignmentRepository.deleteUpcomingAssignment(event.offenderId)
    if (cancelledCheckins > 0 || deletedAssignments > 0) {
      LOGGER.info("Cancelled {} created/scheduled check ins, deleted {} question assignments for CRN {} due to offender deactivation", cancelledCheckins, deletedAssignments, event.offender.crn)
    }
    eventAuditService.recordOffenderEvent(event.auditEventType, event.offender, event.offender.personalDetails, event.reason, event.sensitive)

    appEventPublisher.publishEvent(event)
    return true
  }

  /**
   * Updates records related to offender reactivation. Settings from the event.offender DTO will
   * be used to update the offender record (e.g. checkin schedule).
   *
   * Updates the offender status to VERIFIED, so the passed in entity should have INACTIVE status.
   */
  @Transactional
  fun offenderReactivation(offenderId: Long, event: PartialOffenderReactivatedEvent): OffenderReactivatedEvent? {
    val offender = offenderRepository.findById(offenderId).orElseThrow() // we need to fetch, to be able to refresh later
    val setup = offenderSetupRepository.reactivateOffenderAndCreateSetup(offender, event.reason)
    var finalised: OffenderReactivatedEvent? = null
    if (setup.isPresent) {
      entityManager.refresh(offender) // we want the latest state
      offender.mode = event.offender.mode
      offender.firstCheckin = event.offender.firstCheckin
      offender.checkinInterval = event.offender.checkinInterval?.duration
      offender.contactPreference = event.offender.contactPreference
      offender.currentEvent = event.offender.currentEvent
      offenderRepository.save(offender)

      outboxItemRepository.addOutboxItem(OutboxItemType.OFFENDER_REACTIVATED, setup.get().id)
      finalised = event.finalise(setup.get(), offender)
      eventAuditService.recordOffenderEvent(OffenderAuditEventType.OFFENDER_REACTIVATED, finalised.offender, finalised.offender.personalDetails, finalised.reason)

      appEventPublisher.publishEvent(finalised)
    }

    return finalised
  }

  companion object {
    val LOGGER = logger<OffenderPersistenceService>()

    // TODO ESUP-2223: agreed for now; to change or remove if probation integration take over the NDelius note text
    const val CHECKINS_STOPPED_REASON = "Check ins were stopped"
  }
}
