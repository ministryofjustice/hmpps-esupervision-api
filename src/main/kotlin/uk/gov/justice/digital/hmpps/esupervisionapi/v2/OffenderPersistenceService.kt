package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import java.time.Instant

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
  fun offenderDeactivation(
    offenderId: Long,
    expectedCurrentEvent: Long?,
    checkEvent: Boolean,
    updatedAt: Instant,
    buildEvent: (OffenderDto) -> OffenderDeactivatedEvent,
  ): Boolean {
    if (offenderRepository.deactivateIfUnchanged(offenderId, expectedCurrentEvent, checkEvent, updatedAt) == 0) {
      LOGGER.info("Skipping stale deactivation for offender {}", offenderId)
      return false
    }

    val snapshot = offenderRepository.findDeactivationSnapshot(offenderId).orElseThrow().dto()
    val event = buildEvent(snapshot)
    event.setup?.let {
      outboxItemRepository.addOutboxItem(OutboxItemType.OFFENDER_DEACTIVATED, it.primaryKey)
    }
    val deletedAssignments = questionListAssignmentRepository.deleteUpcomingAssignment(event.offenderId)
    val cancelledCheckins = checkinRepository.updateStatusForOffenderId(event.offenderId, CheckinStatus.CREATED, CheckinStatus.CANCELLED)
    if (cancelledCheckins > 0 || deletedAssignments > 0) {
      LOGGER.info("Cancelled {} created/pending check ins, deleted {} question assignments for CRN {} due to offender deactivation", cancelledCheckins, deletedAssignments, event.offender.crn)
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
  }
}
