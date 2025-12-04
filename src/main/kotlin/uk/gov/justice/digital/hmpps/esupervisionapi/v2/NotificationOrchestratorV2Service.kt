package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * V2 Notification Orchestrator Service Orchestrates notification sending by coordinating between:
 * - NotificationPersistenceService for database operations
 * - NotifyGatewayService for GOV.UK Notify API calls
 * - DomainEventService for publishing domain events
 * - EventAuditV2Service for audit logging
 */
@Service
class NotificationOrchestratorV2Service(
  private val notificationPersistence: NotificationPersistenceService,
  private val notifyGateway: NotifyGatewayService,
  private val domainEventService: DomainEventService,
  private val eventAuditService: EventAuditV2Service,
  private val eventDetailService: EventDetailV2Service,
  private val ndiliusApiClient: NdiliusApiClient,
  private val clock: Clock,
) {
  /** Send notifications for setup completed event */
  fun sendSetupCompletedNotifications(
    offender: OffenderV2,
    contactDetails: ContactDetails? = null,
  ) {
    // Publish event unconditionally - this is the source of truth
    domainEventService.publishSetupCompleted(offender)

    val details = contactDetails ?: ndiliusApiClient.getContactDetails(offender.crn)

    // Record audit even if contact details are missing
    if (details != null) {
      eventAuditService.recordSetupCompleted(offender, details)
    } else {
      LOGGER.warn(
        "Recording audit without contact details for offender {}: contact details not found",
        offender.crn,
      )
      eventAuditService.recordSetupCompleted(offender, null)
    }

    // Notifications are best effort - don't fail the operation if they can't be sent
    if (details == null) {
      LOGGER.warn(
        "Cannot send notifications for offender {}: contact details not found",
        offender.crn,
      )
      return
    }

    try {
      val config = notificationPersistence.getNotificationConfig("SETUP_COMPLETED")
      val personalisation =
        mapOf(
          "date" to LocalDate.now(clock).format(DATE_FORMATTER),
        )

      val notificationsWithRecipients =
        notificationPersistence.buildOffenderNotifications(
          config = config,
          offender = offender,
          contactDetails = details,
          eventType = "SETUP_COMPLETED",
        )

      processAndSendNotifications(notificationsWithRecipients, personalisation)
    } catch (e: Exception) {
      val sanitized = PiiSanitizer.sanitizeException(e, offender.crn, offender.uuid)
      LOGGER.error(
        "Failed to send setup completed notifications for offender {}: {}",
        offender.crn,
        sanitized,
      )
    }
  }

  /** Send notifications for checkin created event */
  fun sendCheckinCreatedNotifications(
    checkin: OffenderCheckinV2,
    contactDetails: ContactDetails? = null,
  ) {
    domainEventService.publishCheckinCreated(checkin)

    val details = contactDetails ?: ndiliusApiClient.getContactDetails(checkin.offender.crn)

    if (details != null) {
      eventAuditService.recordCheckinCreated(checkin, details)
    } else {
      LOGGER.warn(
        "Recording audit without contact details for checkin {}: contact details not found",
        checkin.uuid,
      )
      eventAuditService.recordCheckinCreated(checkin, null)
    }

    if (details == null) {
      LOGGER.warn(
        "Cannot send notifications for checkin {}: contact details not found",
        checkin.uuid,
      )
      return
    }

    try {
      val config = notificationPersistence.getNotificationConfig("CHECKIN_CREATED")
      val personalisation =
        mapOf(
          "date" to checkin.dueDate.format(DATE_FORMATTER),
          "crn" to checkin.offender.crn,
          "offender_name" to "${details.name.forename} ${details.name.surname}",
        )

      val notificationsWithRecipients = mutableListOf<NotificationWithRecipient>()
      notificationsWithRecipients.addAll(
        notificationPersistence.buildOffenderNotifications(
          config = config,
          offender = checkin.offender,
          contactDetails = details,
          eventType = "CHECKIN_CREATED",
        ),
      )
      notificationsWithRecipients.addAll(
        notificationPersistence.buildPractitionerNotifications(
          config = config,
          offender = checkin.offender,
          contactDetails = details,
          checkin = checkin,
          eventType = "CHECKIN_CREATED",
        ),
      )

      processAndSendNotifications(notificationsWithRecipients, personalisation)
    } catch (e: Exception) {
      val sanitized = PiiSanitizer.sanitizeException(e, checkin.offender.crn, checkin.offender.uuid)
      LOGGER.error(
        "Failed to send checkin created notifications for checkin {}: {}",
        checkin.uuid,
        sanitized,
      )
    }
  }

  /** Send notifications for checkin submitted event */
  fun sendCheckinSubmittedNotifications(
    checkin: OffenderCheckinV2,
    contactDetails: ContactDetails? = null,
  ) {
    domainEventService.publishCheckinSubmitted(checkin)

    val details = contactDetails ?: ndiliusApiClient.getContactDetails(checkin.offender.crn)

    if (details != null) {
      eventAuditService.recordCheckinSubmitted(checkin, details)
    } else {
      LOGGER.warn(
        "Recording audit without contact details for checkin {}: contact details not found",
        checkin.uuid,
      )
      eventAuditService.recordCheckinSubmitted(checkin, null)
    }

    if (details == null) {
      LOGGER.warn(
        "Cannot send notifications for checkin {}: contact details not found",
        checkin.uuid,
      )
      return
    }

    try {
      val config = notificationPersistence.getNotificationConfig("CHECKIN_SUBMITTED")
      val personalisation =
        mapOf(
          "date" to LocalDate.now(clock).format(DATE_FORMATTER),
          "crn" to checkin.offender.crn,
          "offender_name" to "${details.name.forename} ${details.name.surname}",
          "due_date" to checkin.dueDate.format(DATE_FORMATTER),
        )

      val notificationsWithRecipients = mutableListOf<NotificationWithRecipient>()
      notificationsWithRecipients.addAll(
        notificationPersistence.buildOffenderNotifications(
          config = config,
          offender = checkin.offender,
          contactDetails = details,
          eventType = "CHECKIN_SUBMITTED",
        ),
      )
      notificationsWithRecipients.addAll(
        notificationPersistence.buildPractitionerNotifications(
          config = config,
          offender = checkin.offender,
          contactDetails = details,
          checkin = checkin,
          eventType = "CHECKIN_SUBMITTED",
        ),
      )

      processAndSendNotifications(notificationsWithRecipients, personalisation)
    } catch (e: Exception) {
      val sanitized = PiiSanitizer.sanitizeException(e, checkin.offender.crn, checkin.offender.uuid)
      LOGGER.error(
        "Failed to send checkin submitted notifications for checkin {}: {}",
        checkin.uuid,
        sanitized,
      )
    }
  }

  /** Send notifications for checkin reviewed event */
  fun sendCheckinReviewedNotifications(
    checkin: OffenderCheckinV2,
    contactDetails: ContactDetails? = null,
  ) {
    // Publish domain event (no GOV.UK Notify by default for this event)
    domainEventService.publishCheckinReviewed(checkin)

    // Record audit event
    val details = contactDetails ?: ndiliusApiClient.getContactDetails(checkin.offender.crn)
    if (details != null) {
      eventAuditService.recordCheckinReviewed(checkin, details)
    } else {
      LOGGER.warn(
        "Cannot record audit for reviewed checkin {}: contact details not found",
        checkin.uuid,
      )
    }
  }

  /** Send notifications for checkin expired event */
  fun sendCheckinExpiredNotifications(
    checkin: OffenderCheckinV2,
    contactDetails: ContactDetails? = null,
  ) {
    val config = notificationPersistence.getNotificationConfig("CHECKIN_EXPIRED")
    val details = contactDetails ?: ndiliusApiClient.getContactDetails(checkin.offender.crn)

    if (details == null) {
      LOGGER.warn(
        "Cannot send notifications for checkin {}: contact details not found",
        checkin.uuid,
      )
      // Continue with domain event even if contact details not found
    } else {
      val personalisation =
        mapOf(
          "date" to LocalDate.now(clock).format(DATE_FORMATTER),
          "crn" to checkin.offender.crn,
          "offender_name" to "${details.name.forename} ${details.name.surname}",
          "due_date" to checkin.dueDate.format(DATE_FORMATTER),
        )

      // Build notifications (practitioner only for expired checkins)
      val notificationsWithRecipients =
        notificationPersistence.buildPractitionerNotifications(
          config = config,
          offender = checkin.offender,
          contactDetails = details,
          checkin = checkin,
          eventType = "CHECKIN_EXPIRED",
        )

      processAndSendNotifications(notificationsWithRecipients, personalisation)
    }

    // Publish domain event
    domainEventService.publishCheckinExpired(checkin)

    // Record audit event
    if (details != null) {
      eventAuditService.recordCheckinExpired(checkin, details)
    } else {
      LOGGER.warn(
        "Cannot record audit for expired checkin {}: contact details not found",
        checkin.uuid,
      )
    }
  }

  /** Get event detail for a given URL */
  fun getEventDetail(detailUrl: String): EventDetailResponse? = eventDetailService.getEventDetail(detailUrl)

  // Private helper methods

  private fun processAndSendNotifications(
    notificationsWithRecipients: List<NotificationWithRecipient>,
    personalisation: Map<String, String>,
  ) {
    if (notificationsWithRecipients.isEmpty()) return

    // Save notifications to database
    val savedNotifications =
      notificationPersistence.saveNotifications(
        notificationsWithRecipients.map { it.notification },
      )

    // Reconstruct wrappers with saved entities
    val notificationsToSend =
      savedNotifications.zip(notificationsWithRecipients).map { (saved, wrapper) ->
        NotificationWithRecipient(saved, wrapper.recipient)
      }

    // Send notifications via GOV.UK Notify and update status immediately after each send
    notificationsToSend.forEach { wrapper ->
      val notification = wrapper.notification
      val recipient = wrapper.recipient

      try {
        val notifyId =
          notifyGateway.send(
            channel = notification.channel,
            templateId = notification.templateId!!,
            recipient = recipient,
            personalisation = personalisation,
            reference = notification.reference,
          )

        LOGGER.info(
          "Sent {} notification to {} for offender {}, notificationId={}",
          notification.channel,
          notification.recipientType,
          notification.offender?.crn ?: "unknown",
          notifyId,
        )

        // Update status immediately in own transaction
        notificationPersistence.updateSingleNotificationStatus(
          notification = notification,
          success = true,
          notifyId = notifyId,
        )
      } catch (e: Exception) {
        val sanitized =
          PiiSanitizer.sanitizeException(
            e,
            notification.offender?.crn,
            notification.offender?.uuid,
          )
        LOGGER.error(
          "Failed to send {} to {}: {}",
          notification.channel,
          notification.recipientType,
          sanitized,
        )

        // Update status immediately in own transaction
        notificationPersistence.updateSingleNotificationStatus(
          notification = notification,
          success = false,
          notifyId = UUID.randomUUID(), // Placeholder ID for failed sends
          error = PiiSanitizer.sanitizeMessage(e.message ?: "Unknown error", null, null),
        )
      }
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(NotificationOrchestratorV2Service::class.java)
    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy")
  }
}
