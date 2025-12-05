package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * Service responsible for all notification database operations Uses TransactionTemplate for
 * explicit transaction management
 */
@Service
class NotificationPersistenceService(
  private val notificationConfigRepository: NotificationConfigRepository,
  private val genericNotificationV2Repository: GenericNotificationV2Repository,
  private val transactionTemplate: TransactionTemplate,
  private val clock: Clock,
) {
  /** Get notification configuration for an event type */
  fun getNotificationConfig(eventType: String): NotificationConfig = notificationConfigRepository.findByEventType(eventType).orElseThrow {
    IllegalStateException("Notification configuration not found for event type: $eventType")
  }

  /** Build notification records for offender (SMS and/or Email) */
  fun buildOffenderNotifications(
    config: NotificationConfig,
    offender: OffenderV2,
    contactDetails: ContactDetails,
    eventType: String,
  ): List<NotificationWithRecipient> {
    val notifications = mutableListOf<NotificationWithRecipient>()

    // SMS notification
    if (config.offenderSmsEnabled &&
      contactDetails.mobile != null &&
      config.offenderSmsTemplateId != null
    ) {
      val notification =
        GenericNotificationV2(
          notificationId = UUID.randomUUID(),
          eventType = eventType,
          recipientType = "OFFENDER",
          channel = "SMS",
          offender = offender,
          practitionerId = null,
          status = "created",
          reference = offender.uuid.toString(),
          createdAt = clock.instant(),
          errorMessage = null,
          templateId = config.offenderSmsTemplateId,
          sentAt = null,
          updatedAt = null,
        )
      notifications.add(NotificationWithRecipient(notification, contactDetails.mobile))
    }

    // Email notification
    if (config.offenderEmailEnabled &&
      contactDetails.email != null &&
      config.offenderEmailTemplateId != null
    ) {
      val notification =
        GenericNotificationV2(
          notificationId = UUID.randomUUID(),
          eventType = eventType,
          recipientType = "OFFENDER",
          channel = "EMAIL",
          offender = offender,
          practitionerId = null,
          status = "created",
          reference = offender.uuid.toString(),
          createdAt = clock.instant(),
          errorMessage = null,
          templateId = config.offenderEmailTemplateId,
          sentAt = null,
          updatedAt = null,
        )
      notifications.add(NotificationWithRecipient(notification, contactDetails.email))
    }

    return notifications
  }

  /** Build notification records for practitioner (Email only) */
  fun buildPractitionerNotifications(
    config: NotificationConfig,
    offender: OffenderV2,
    contactDetails: ContactDetails,
    checkin: OffenderCheckinV2?,
    eventType: String,
  ): List<NotificationWithRecipient> {
    val notifications = mutableListOf<NotificationWithRecipient>()
    val practitionerDetails = contactDetails.practitioner

    if (practitionerDetails == null) {
      LOGGER.warn(
        "Cannot build practitioner notifications for offender {}: practitioner details not available",
        offender.crn,
      )
      return notifications
    }

    // Email notification (SMS for practitioners not currently supported)
    if (config.practitionerEmailEnabled && config.practitionerEmailTemplateId != null) {
      val reference = checkin?.uuid?.toString() ?: offender.uuid.toString()
      val notification =
        GenericNotificationV2(
          notificationId = UUID.randomUUID(),
          eventType = eventType,
          recipientType = "PRACTITIONER",
          channel = "EMAIL",
          offender = offender,
          practitionerId = offender.practitionerId,
          status = "created",
          reference = reference,
          createdAt = clock.instant(),
          errorMessage = null,
          templateId = config.practitionerEmailTemplateId,
          sentAt = null,
          updatedAt = null,
        )
      notifications.add(NotificationWithRecipient(notification, practitionerDetails.email))
    }

    return notifications
  }

  /** Save notification records in a transaction */
  fun saveNotifications(notifications: List<GenericNotificationV2>): List<GenericNotificationV2> {
    if (notifications.isEmpty()) return emptyList()

    return transactionTemplate.execute {
      genericNotificationV2Repository.saveAll(notifications).toList()
    }
      ?: emptyList()
  }

  /** Update single notification status immediately after sending (in own transaction) */
  fun updateSingleNotificationStatus(
    notification: GenericNotificationV2,
    success: Boolean,
    notifyId: UUID,
    error: String? = null,
  ) {
    transactionTemplate.execute {
      notification.notificationId = notifyId
      notification.status = if (success) "sent" else "failed"
      notification.sentAt = if (success) clock.instant() else null
      notification.errorMessage = error
      notification.updatedAt = clock.instant()
      genericNotificationV2Repository.save(notification)
    }
  }

  /** Update notification statuses after sending (batch) - kept for backward compatibility */
  fun updateNotificationStatuses(results: List<SendResult>) {
    if (results.isEmpty()) return

    transactionTemplate.execute {
      results.forEach { result ->
        result.notification.notificationId = result.notificationId
        result.notification.status = if (result.success) "sent" else "failed"
        result.notification.sentAt = if (result.success) clock.instant() else null
        result.notification.errorMessage = result.error
        result.notification.updatedAt = clock.instant()
      }
      genericNotificationV2Repository.saveAll(results.map { it.notification })
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(NotificationPersistenceService::class.java)
  }
}

// Data classes for notification processing
data class NotificationWithRecipient(
  val notification: GenericNotificationV2,
  val recipient: String, // Phone number or email address
)

data class SendResult(
  val notification: GenericNotificationV2,
  val notificationId: UUID,
  val success: Boolean,
  val error: String? = null,
)
