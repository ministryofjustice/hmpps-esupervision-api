package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * V2 Notification Orchestrator Service Orchestrates notification sending by coordinating between:
 * - NotificationPersistenceService for building and persisting notifications
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
    domainEventService.publishSetupCompleted(offender)

    val details = contactDetails ?: ndiliusApiClient.getContactDetails(offender.crn)

    if (details != null) {
      eventAuditService.recordSetupCompleted(offender, details)
    } else {
      LOGGER.warn(
        "Recording audit without contact details for offender {}: contact details not found",
        offender.crn,
      )
      eventAuditService.recordSetupCompleted(offender, null)
    }

    if (details == null) {
      LOGGER.warn(
        "Cannot send notifications for offender {}: contact details not found",
        offender.crn,
      )
      return
    }

    try {
      val personalisation =
        mapOf(
          "date" to LocalDate.now(clock).format(DATE_FORMATTER),
        )

      val notificationsWithRecipients =
        notificationPersistence.buildOffenderNotifications(
          offender = offender,
          contactDetails = details,
          notificationType = NotificationType.RegistrationConfirmation,
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
      val personalisation =
        mapOf(
          "date" to checkin.dueDate.format(DATE_FORMATTER),
          "crn" to checkin.offender.crn,
          "offender_name" to "${details.name.forename} ${details.name.surname}",
        )

      // V1 only notifies offender for checkin invite (no practitioner template)
      val notificationsWithRecipients =
        notificationPersistence.buildOffenderNotifications(
          offender = checkin.offender,
          contactDetails = details,
          notificationType = NotificationType.OffenderCheckinInvite,
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
          offender = checkin.offender,
          contactDetails = details,
          notificationType = NotificationType.OffenderCheckinSubmitted,
        ),
      )
      notificationsWithRecipients.addAll(
        notificationPersistence.buildPractitionerNotifications(
          offender = checkin.offender,
          contactDetails = details,
          checkin = checkin,
          notificationType = NotificationType.PractitionerCheckinSubmitted,
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
    domainEventService.publishCheckinReviewed(checkin)

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
    val details = contactDetails ?: ndiliusApiClient.getContactDetails(checkin.offender.crn)

    if (details == null) {
      LOGGER.warn(
        "Cannot send notifications for checkin {}: contact details not found",
        checkin.uuid,
      )
    } else {
      val personalisation =
        mapOf(
          "date" to LocalDate.now(clock).format(DATE_FORMATTER),
          "crn" to checkin.offender.crn,
          "offender_name" to "${details.name.forename} ${details.name.surname}",
          "due_date" to checkin.dueDate.format(DATE_FORMATTER),
        )

      val notificationsWithRecipients =
        notificationPersistence.buildPractitionerNotifications(
          offender = checkin.offender,
          contactDetails = details,
          checkin = checkin,
          notificationType = NotificationType.PractitionerCheckinMissed,
        )

      processAndSendNotifications(notificationsWithRecipients, personalisation)
    }

    domainEventService.publishCheckinExpired(checkin)

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

  private fun processAndSendNotifications(
    notificationsWithRecipients: List<NotificationWithRecipient>,
    personalisation: Map<String, String>,
  ) {
    if (notificationsWithRecipients.isEmpty()) return

    val savedNotifications =
      notificationPersistence.saveNotifications(
        notificationsWithRecipients.map { it.notification },
      )

    val notificationsToSend =
      savedNotifications.zip(notificationsWithRecipients).map { (saved, wrapper) ->
        NotificationWithRecipient(saved, wrapper.recipient)
      }

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

        notificationPersistence.updateSingleNotificationStatus(
          notification = notification,
          success = false,
          notifyId = UUID.randomUUID(),
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
