package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderDeactivatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.activeEventNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Clock

/**
 * Encapsulates the offender deactivation flow so it can be reused by both the
 * practitioner-initiated endpoint ([OffenderResource.deactivateOffender]) and the
 * scheduled jobs that detect offenders who should no longer receive online check-ins
 * (no active probation events, or contact suspended/in reset in NDelius).
 *
 * Deactivating an offender:
 * - sets their status to INACTIVE
 * - cancels any pending (CREATED) check-ins
 * - deletes any upcoming question list assignment
 * - records an OFFENDER_DEACTIVATED audit event
 * - sends "check-ins stopped" notifications (publishes V2_SETUP_REMOVED)
 */
@Service
class OffenderDeactivationService(
  private val clock: Clock,
  private val offenderSetupRepository: OffenderSetupRepository,
  private val offenderPersistenceService: OffenderPersistenceService,
) {

  /**
   * Deactivate a VERIFIED offender. Idempotent: a no-op if the offender is not VERIFIED.
   *
   * @param reason recorded against the deactivation audit event
   * @param contactDetails Delius details fetched by the caller; downstream audit/notifications are skipped when null
   * @param sensitive whether the reason contains sensitive information
   * @param auditEventType the audit event type to record; defaults to a manual practitioner
   *   deactivation. Scheduled jobs pass a criterion-specific automated type so the reason an offender
   *   was stopped (in reset vs no active events) is queryable via the audit event_type.
   * @return the (possibly unchanged) offender
   *
   * Spins its own transaction (REQUIRES_NEW) so that a deactivation commits independently of any
   * surrounding work. In particular, when called from [V2CheckinCreationJob]'s long-lived job
   * transaction this prevents a failure in one offender's audit/persistence (which would otherwise
   * mark the shared transaction rollback-only) from rolling back every other offender's check-in
   * creation and deactivation in the same run. It also makes each deactivation atomic for the
   * non-transactional callers (the reminder job and the deactivate endpoint).
   */
  fun deactivateOffender(
    offender: Offender,
    reason: String,
    contactDetails: ContactDetails? = null,
    sensitive: Boolean = false,
    auditEventType: OffenderAuditEventType = OffenderAuditEventType.OFFENDER_DEACTIVATED,
  ): Offender {
    if (offender.status != OffenderStatus.VERIFIED) {
      LOGGER.info("Skipping deactivation for CRN {}: status is {}", offender.crn, offender.status)
      return offender
    }

    offender.status = OffenderStatus.INACTIVE
    offender.updatedAt = clock.instant()

    val setup = offenderSetupRepository.findByOffender(offender).orElse(null)
    val event = OffenderDeactivatedEvent(
      offenderId = offender.id,
      offender = offender.dto(contactDetails),
      auditEventType = auditEventType,
      setup = setup?.let { Pair(it.id, it.uuid) },
      activeEventNumber = contactDetails?.let { activeEventNumber(offender, it) },
      reason = reason,
      sensitive = sensitive,
    )

    offenderPersistenceService.offenderDeactivation(offender, event)

    return offender
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(OffenderDeactivationService::class.java)
  }
}
