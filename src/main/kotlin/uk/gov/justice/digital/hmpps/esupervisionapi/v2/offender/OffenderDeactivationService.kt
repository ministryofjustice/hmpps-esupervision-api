package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.question.QuestionService
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
  private val offenderRepository: OffenderRepository,
  private val checkinRepository: OffenderCheckinRepository,
  private val offenderSetupRepository: OffenderSetupRepository,
  private val questionService: QuestionService,
  private val eventAuditService: EventAuditService,
  private val notificationService: NotificationService,
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
   * Runs in its own transaction (REQUIRES_NEW) so that a deactivation commits independently of any
   * surrounding work. In particular, when called from [V2CheckinCreationJob]'s long-lived job
   * transaction this prevents a failure in one offender's audit/persistence (which would otherwise
   * mark the shared transaction rollback-only) from rolling back every other offender's check-in
   * creation and deactivation in the same run. It also makes each deactivation atomic for the
   * non-transactional callers (the reminder job and the deactivate endpoint).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
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
    val saved = offenderRepository.save(offender)

    questionService.deleteUpcomingAssignment(saved.crn)

    // set any pending check ins to cancelled
    val cancelled = checkinRepository.updateStatusForOffender(saved, CheckinStatus.CREATED, CheckinStatus.CANCELLED)
    if (cancelled > 0) {
      LOGGER.info("Cancelled {} created/pending check ins for CRN {} due to offender deactivation", cancelled, saved.crn)
    }

    eventAuditService.recordOffenderEvent(auditEventType, saved, contactDetails, reason, sensitive)

    val setup = offenderSetupRepository.findByOffender(saved).orElse(null)
    try {
      notificationService.sendDeactivationCompletedNotifications(saved, contactDetails, setup?.setupId())
    } catch (e: Exception) {
      LOGGER.warn("Failed to send deactivation completed notifications for offender {}", saved.uuid, e)
    }

    return saved
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(OffenderDeactivationService::class.java)
  }
}
