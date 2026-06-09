package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.config.MessageTemplateConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.MessageTypeTemplateConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.NotificationChannelsConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Email
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationMethod
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PhoneNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotificationContextV2
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
  @param:Value("\${app.env}") private val env: String,
) {
  /** Build notification records for offender (SMS and/or Email) */
  fun buildOffenderNotifications(
    offenderId: Long,
    crn: CRN,
    contactPreference: ContactPreference,
    contactDetails: ContactDetails,
    notificationType: NotificationType,
  ): List<NotificationWithRecipient> {
    val notifications = mutableListOf<NotificationWithRecipient>()
    val channels = templateConfig.channels

    if (!channels.enabledFor(contactPreference)) {
      return notifications
    }

    fun createNotificationRecord(details: ContactDetails): Pair<GenericNotificationV2, NotificationMethod>? {
      val templates = templateConfig.templatesFor(contactPreference, details)
      val notification = if (templates != null) {
        GenericNotificationV2(
          notificationId = UUID.randomUUID(),
          eventType = notificationType.name,
          recipientType = "OFFENDER",
          channel = when (contactPreference) {
            ContactPreference.PHONE -> "SMS"
            ContactPreference.EMAIL -> "EMAIL"
          },
          offenderId = offenderId,
          practitionerId = null,
          status = "created",
          reference = NotificationContextV2.generateReference(notificationType, clock, env),
          createdAt = clock.instant(),
          errorMessage = null,
          templateId = templates.first.getTemplate(notificationType),
          sentAt = null,
          updatedAt = null,
        )
      } else {
        LOGGER.warn("NOTIFICATION_UNDELIVERABLE: Unsupported contact preference [type={}, crn={}, preference={}]", notificationType, crn, contactPreference)
        return null
      }

      return Pair(notification, templates.second)
    }

    val pair = createNotificationRecord(contactDetails)
    if (pair != null) {
      notifications.add(
        NotificationWithRecipient(
          pair.first,
          when (contactPreference) {
            ContactPreference.PHONE -> (pair.second as PhoneNumber).phoneNumber
            ContactPreference.EMAIL -> (pair.second as Email).email
          },
          AssociatedOffenderInfo(crn),
        ),
      )
    }

    return notifications
  }

  /** Build notification records for practitioner (Email only) */
  fun buildPractitionerNotifications(
    offenderId: Long?,
    crn: CRN?,
    contactDetails: PractitionerDetails?,
    checkin: CheckinV2Dto?,
    notificationType: NotificationType,
    practitionerId: ExternalUserId,
  ): List<NotificationWithRecipient> {
    val notifications = mutableListOf<NotificationWithRecipient>()
    val channels = templateConfig.channels
    if (channels.practitionerEmailEnabled) {
      if (contactDetails?.email == null) {
        val reason = if (contactDetails == null) "details missing" else "no email"
        LOGGER.warn("NOTIFICATION_UNDELIVERABLE: [reason={} type={}, crn={}]", reason, notificationType, crn)
      } else {
        val emailTemplateId = templateConfig.templatesFor(Email(contactDetails.email)).getTemplate(notificationType)
        val reference = NotificationContextV2.generateReference(notificationType, clock, env)
        val notification = GenericNotificationV2(
          notificationId = UUID.randomUUID(),
          eventType = notificationType.name,
          recipientType = "PRACTITIONER",
          channel = "EMAIL",
          offenderId = offenderId,
          practitionerId = practitionerId,
          status = "created",
          reference = reference,
          createdAt = clock.instant(),
          errorMessage = null,
          templateId = emailTemplateId,
          sentAt = null,
          updatedAt = null,
        )
        notifications.add(NotificationWithRecipient(notification, contactDetails.email, AssociatedOffenderInfo.create(crn)))
      }
    }

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

data class AssociatedOffenderInfo(val crn: CRN) {
  companion object {
    fun create(crn: CRN?): AssociatedOffenderInfo? = crn?.let { AssociatedOffenderInfo(crn) }
  }
}


data class NotificationWithRecipient(
  val notification: GenericNotificationV2,
  /** Phone number or email address */
  val recipient: String,
  /** Offender associated with the notification (which may be different than the recipient) */
  val offender: AssociatedOffenderInfo?
)

data class SendResult(
  val notification: GenericNotificationV2,
  val notificationId: UUID,
  val success: Boolean,
  val error: String? = null,
)

private fun NotificationChannelsConfig.enabledFor(preference: ContactPreference): Boolean {
  when (preference) {
    ContactPreference.PHONE -> return offenderSmsEnabled
    ContactPreference.EMAIL -> return offenderEmailEnabled
  }
}

private fun MessageTemplateConfig.templatesFor(preference: ContactPreference, contactDetails: ContactDetails): Pair<MessageTypeTemplateConfig, NotificationMethod>? = when (preference) {
  ContactPreference.PHONE -> if (contactDetails.mobile == null) null else Pair(this.templatesFor(PhoneNumber(contactDetails.mobile)), PhoneNumber(contactDetails.mobile))
  ContactPreference.EMAIL -> if (contactDetails.email == null) null else Pair(this.templatesFor(Email(contactDetails.email)), Email(contactDetails.email))
}
