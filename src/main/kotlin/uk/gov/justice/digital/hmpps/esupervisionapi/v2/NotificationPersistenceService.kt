package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.config.MessageTemplateConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Email
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PhoneNumber
import java.time.Clock
import java.util.UUID

/**
 * Service responsible for building and persisting notification records.
 * Uses V1 MessageTemplateConfig for template IDs.
 */
@Service
class NotificationPersistenceService(
  private val templateConfig: MessageTemplateConfig,
  private val genericNotificationV2Repository: GenericNotificationV2Repository,
  private val transactionTemplate: TransactionTemplate,
  private val clock: Clock,
) {
  /** Build notification records for offender (SMS and/or Email) */
  fun buildOffenderNotifications(
    offender: OffenderV2,
    contactDetails: ContactDetails,
    notificationType: NotificationType,
  ): List<NotificationWithRecipient> {
    val notifications = mutableListOf<NotificationWithRecipient>()
    val channels = templateConfig.channels

    // SMS notification
    if (channels.offenderSmsEnabled) {
      if (contactDetails.mobile == null) {
        LOGGER.error(
          "NOTIFICATION_UNDELIVERABLE: Offender has no mobile number [type={}, crn={}, offenderUuid={}]",
          notificationType,
          offender.crn,
          offender.uuid,
        )
      } else {
        val smsTemplateId = templateConfig.templatesFor(PhoneNumber(contactDetails.mobile)).getTemplate(notificationType)
        val notification = GenericNotificationV2(
          notificationId = UUID.randomUUID(),
          eventType = notificationType.name,
          recipientType = "OFFENDER",
          channel = "SMS",
          offender = offender,
          practitionerId = null,
          status = "created",
          reference = offender.uuid.toString(),
          createdAt = clock.instant(),
          errorMessage = null,
          templateId = smsTemplateId,
          sentAt = null,
          updatedAt = null,
        )
        notifications.add(NotificationWithRecipient(notification, contactDetails.mobile))
      }
    }

    // Email notification
    if (channels.offenderEmailEnabled) {
      if (contactDetails.email == null) {
        LOGGER.error(
          "NOTIFICATION_UNDELIVERABLE: Offender has no email address [type={}, crn={}, offenderUuid={}]",
          notificationType,
          offender.crn,
          offender.uuid,
        )
      } else {
        val emailTemplateId = templateConfig.templatesFor(Email(contactDetails.email)).getTemplate(notificationType)
        val notification = GenericNotificationV2(
          notificationId = UUID.randomUUID(),
          eventType = notificationType.name,
          recipientType = "OFFENDER",
          channel = "EMAIL",
          offender = offender,
          practitionerId = null,
          status = "created",
          reference = offender.uuid.toString(),
          createdAt = clock.instant(),
          errorMessage = null,
          templateId = emailTemplateId,
          sentAt = null,
          updatedAt = null,
        )
        notifications.add(NotificationWithRecipient(notification, contactDetails.email))
      }
    }

    return notifications
  }

  /** Build notification records for practitioner (Email only) */
  fun buildPractitionerNotifications(
    offender: OffenderV2,
    contactDetails: ContactDetails,
    checkin: OffenderCheckinV2?,
    notificationType: NotificationType,
  ): List<NotificationWithRecipient> {
    val notifications = mutableListOf<NotificationWithRecipient>()
    val channels = templateConfig.channels

    if (!channels.practitionerEmailEnabled) {
      return notifications
    }

    val practitionerDetails = contactDetails.practitioner

    if (practitionerDetails == null) {
      LOGGER.error(
        "NOTIFICATION_UNDELIVERABLE: Practitioner details not available [type={}, crn={}, offenderUuid={}]",
        notificationType,
        offender.crn,
        offender.uuid,
      )
      return notifications
    }

    if (practitionerDetails.email == null) {
      LOGGER.error(
        "NOTIFICATION_UNDELIVERABLE: Practitioner has no email address [type={}, crn={}, offenderUuid={}]",
        notificationType,
        offender.crn,
        offender.uuid,
      )
      return notifications
    }

    val emailTemplateId = templateConfig.templatesFor(Email(practitionerDetails.email)).getTemplate(notificationType)
    val reference = checkin?.uuid?.toString() ?: offender.uuid.toString()
    val notification = GenericNotificationV2(
      notificationId = UUID.randomUUID(),
      eventType = notificationType.name,
      recipientType = "PRACTITIONER",
      channel = "EMAIL",
      offender = offender,
      practitionerId = offender.practitionerId,
      status = "created",
      reference = reference,
      createdAt = clock.instant(),
      errorMessage = null,
      templateId = emailTemplateId,
      sentAt = null,
      updatedAt = null,
    )
    notifications.add(NotificationWithRecipient(notification, practitionerDetails.email))

    return notifications
  }

  /** Save notification records in a transaction */
  fun saveNotifications(notifications: List<GenericNotificationV2>): List<GenericNotificationV2> {
    if (notifications.isEmpty()) return emptyList()

    return transactionTemplate.execute {
      genericNotificationV2Repository.saveAll(notifications).toList()
    } ?: emptyList()
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

  companion object {
    private val LOGGER = LoggerFactory.getLogger(NotificationPersistenceService::class.java)
  }
}

data class NotificationWithRecipient(
  val notification: GenericNotificationV2,
  val recipient: String,
)

data class SendResult(
  val notification: GenericNotificationV2,
  val notificationId: UUID,
  val success: Boolean,
  val error: String? = null,
)
