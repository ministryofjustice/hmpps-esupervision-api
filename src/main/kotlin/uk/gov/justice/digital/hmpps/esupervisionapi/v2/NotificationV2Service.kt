package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.springframework.stereotype.Service

/**
 * V2 Notification Service
 * Maintains backward compatibility by delegating to NotificationOrchestratorV2Service
 * This allows existing code to continue using NotificationV2Service while the actual
 * logic is now properly separated into specialized services
 */
@Service
class NotificationV2Service(
  private val orchestrator: NotificationOrchestratorV2Service,
) {
  /**
   * Send notifications for setup completed event
   */
  fun sendSetupCompletedNotifications(offender: OffenderV2, contactDetails: ContactDetails? = null) {
    orchestrator.sendSetupCompletedNotifications(offender, contactDetails)
  }

  /**
   * Send notifications for checkin created event
   */
  fun sendCheckinCreatedNotifications(checkin: OffenderCheckinV2, contactDetails: ContactDetails) {
    orchestrator.sendCheckinCreatedNotifications(checkin, contactDetails)
  }

  /**
   * Send notifications for checkin submitted event
   */
  fun sendCheckinSubmittedNotifications(checkin: OffenderCheckinV2, contactDetails: ContactDetails) {
    orchestrator.sendCheckinSubmittedNotifications(checkin, contactDetails)
  }

  /**
   * Send notifications for checkin reviewed event
   */
  fun sendCheckinReviewedNotifications(checkin: OffenderCheckinV2, contactDetails: ContactDetails) {
    orchestrator.sendCheckinReviewedNotifications(checkin, contactDetails)
  }

  /**
   * Send notifications for checkin expired event
   */
  fun sendCheckinExpiredNotifications(checkin: OffenderCheckinV2, contactDetails: ContactDetails) {
    orchestrator.sendCheckinExpiredNotifications(checkin, contactDetails)
  }

  /**
   * Get event detail for a given URL
   */
  fun getEventDetail(detailUrl: String): EventDetailResponse? = orchestrator.getEventDetail(detailUrl)
}
