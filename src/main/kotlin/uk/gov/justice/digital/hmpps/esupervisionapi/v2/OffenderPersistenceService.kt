package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType

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
    offenderRepository.save(offender)
    if (event.setup != null) {
      outboxItemRepository.addOutboxItem(OutboxItemType.OFFENDER_DEACTIVATED, event.setup.first)
    }
    val deletedAssignments = questionListAssignmentRepository.deleteUpcomingAssignment(offender.id)
    val cancelledCheckins = checkinRepository.updateStatusForOffender(offender, CheckinStatus.CREATED, CheckinStatus.CANCELLED)
    if (cancelledCheckins > 0 || deletedAssignments > 0) {
      LOGGER.info("Cancelled {} created/pending check ins, deleted {} question assignments for CRN {} due to offender deactivation", cancelledCheckins, deletedAssignments, offender.crn)
    }
    eventAuditService.recordOffenderEvent(event.auditEventType, event.offender, event.offender.personalDetails, event.reason, event.sensitive)

    appEventPublisher.publishEvent(event)
  }

  /**
   * Updates records related to offender reactivation.
   *
   * Updates the offender status to VERIFIED, so the passed in entity should have INACTIVE status.
   */
  @Transactional
  fun offenderReactivation(offender: Offender, event: PartialOffenderReactivatedEvent): OffenderReactivatedEvent? {
    val offender = offenderRepository.findById(offender.id).orElseThrow() // we need to fetch, to be able to refresh later
    val setup = offenderSetupRepository.createReactivationSetupRecord(offender)
    var finalised: OffenderReactivatedEvent? = null
    if (setup.isPresent) {
      entityManager.refresh(offender) // we want the latest state
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
