package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.QuestionsReminderInfo
import java.util.UUID

/**
 * V2 Notification Service
 * Maintains backward compatibility by delegating to NotificationOrchestratorV2Service
 * This allows existing code to continue using NotificationV2Service while the actual
 * logic is now properly separated into specialized services
 */
@Service
class NotificationService(
  private val orchestrator: NotificationOrchestratorService,
) {
  /**
   * Send notifications for setup completed event
   */
  fun sendSetupCompletedNotifications(offender: Offender, contactDetails: ContactDetails? = null, setup: OffenderSetupDto) {
    orchestrator.sendSetupCompletedNotifications(offender, contactDetails, setup)
  }

  /**
   * Send notifications for deactivation completed event
   */
  fun sendDeactivationCompletedNotifications(offender: Offender, contactDetails: ContactDetails? = null, setupId: UUID? = null, outcomeCode: String? = null) {
    orchestrator.sendDeactivationCompletedNotifications(offender, contactDetails, setupId, outcomeCode)
  }

  /**
   * Send notifications for reactivation completed event
   */
  fun sendReactivationCompletedNotifications(offender: Offender, contactDetails: ContactDetails? = null, setupId: UUID? = null) {
    orchestrator.sendReactivationCompletedNotifications(offender, contactDetails, setupId)
  }

  /**
   * Send notifications for checkin created event
   */
  fun sendCheckinCreatedNotifications(event: CheckinCreatedEvent) {
    orchestrator.sendCheckinCreatedNotifications(event)
  }

  /**
   * Send notifications for checkin submitted event
   */
  fun sendCheckinSubmittedNotifications(event: CheckinSubmittedEvent) {
    orchestrator.sendCheckinSubmittedNotifications(event)
  }

  /**
   * Send notifications for checkin reviewed event
   */
  fun sendCheckinReviewedNotifications(event: CheckinReviewedEvent) {
    orchestrator.sendCheckinReviewedNotifications(event)
  }

  /**
   * Send notifications for checkin expired event
   */
  fun sendCheckinExpiredNotifications(checkin: OffenderCheckin, contactDetails: ContactDetails) {
    orchestrator.sendCheckinExpiredNotifications(checkin, contactDetails)
  }

  /**
   * Send reminder notifications for checkin event
   */
  fun sendCheckinReminderNotifications(checkin: OffenderCheckin, contactDetails: ContactDetails) {
    orchestrator.sendReminderCheckinNotifications(checkin, contactDetails)
  }

  /**
   * Send reminder for practitioner to add custom questions
   */
  fun sendPractitionerCustomQuestionsReminder(info: QuestionsReminderInfo) {
    orchestrator.sendPractitionerCustomQuestionsReminder(info)
  }

  /**
   * Send notifications for checkin updated event
   */
  fun sendCheckinUpdatedNotifications(event: CheckinAnnotatedEvent) {
    orchestrator.sendCheckinUpdatedNotifications(event)
  }

  /**
   * Get event detail for a given URL
   */
  fun getEventDetail(detailUrl: String): EventDetailResponse? = orchestrator.getEventDetail(detailUrl)
}
