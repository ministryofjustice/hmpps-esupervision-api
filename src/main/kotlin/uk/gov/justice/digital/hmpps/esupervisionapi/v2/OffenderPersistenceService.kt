package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.activeEventNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus

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
) {

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  fun offenderDeactivation(offender: Offender, event: OffenderDeactivatedEvent) {
    val currentOffender = offenderRepository.findByIdForUpdate(offender.id).orElse(null) ?: return
    val deactivationDependsOnCurrentEvent = event.auditEventType in setOf(
      OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS,
      OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_SELECTED_EVENT_NO_LONGER_ACTIVE,
    )
    if (
      currentOffender.status != OffenderStatus.VERIFIED ||
      (deactivationDependsOnCurrentEvent && currentOffender.currentEvent != event.offender.currentEvent)
    ) {
      LOGGER.info(
        "Skipping stale deactivation for CRN {}: persisted status={}, currentEvent={}",
        currentOffender.crn,
        currentOffender.status,
        currentOffender.currentEvent,
      )
      offender.status = currentOffender.status
      offender.currentEvent = currentOffender.currentEvent
      offender.updatedAt = currentOffender.updatedAt
      return
    }

    currentOffender.status = OffenderStatus.INACTIVE
    currentOffender.updatedAt = offender.updatedAt
    val setup = offenderSetupRepository.findByOffender(currentOffender).orElse(null)?.let { SetupInfo.from(it) }
    val persistedEvent = event.copy(
      offender = currentOffender.dto(event.offender.personalDetails),
      setup = setup,
      activeEventNumber = event.offender.personalDetails?.let { activeEventNumber(currentOffender, it) },
    )
    offenderRepository.save(currentOffender)
    if (setup != null) {
      outboxItemRepository.addOutboxItem(OutboxItemType.OFFENDER_DEACTIVATED, setup.primaryKey)
    }
    val deletedAssignments = questionListAssignmentRepository.deleteUpcomingAssignment(currentOffender.id)
    val cancelledCheckins = checkinRepository.updateStatusForOffender(currentOffender, CheckinStatus.CREATED, CheckinStatus.CANCELLED)
    if (cancelledCheckins > 0 || deletedAssignments > 0) {
      LOGGER.info("Cancelled {} created/pending check ins, deleted {} question assignments for CRN {} due to offender deactivation", cancelledCheckins, deletedAssignments, currentOffender.crn)
    }
    eventAuditService.recordOffenderEvent(persistedEvent.auditEventType, persistedEvent.offender, persistedEvent.offender.personalDetails, persistedEvent.reason, persistedEvent.sensitive)

    appEventPublisher.publishEvent(persistedEvent)
    offender.status = currentOffender.status
    offender.currentEvent = currentOffender.currentEvent
    offender.updatedAt = currentOffender.updatedAt
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
      offender.currentEvent = event.currentEvent
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
  }
}
