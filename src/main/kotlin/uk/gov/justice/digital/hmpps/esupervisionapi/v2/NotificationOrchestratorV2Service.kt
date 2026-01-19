package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import java.time.Clock
import java.time.Duration
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * We don't want the cause here because we 1) log it here, 2) need to sanitize any PII
 */
class NotificationFailureException(message: String) : RuntimeException(message)

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
  private val ndiliusApiClient: INdiliusApiClient,
  private val appConfig: AppConfig,
  private val clock: Clock,
  @Value("\${app.scheduling.checkin-notification.window:72h}") private val checkinWindow: Duration,
) {
  private val checkinWindowPeriod = Period.ofDays(checkinWindow.toDays().toInt())

  /** Send notifications for setup completed event */
  fun sendSetupCompletedNotifications(
    offender: OffenderV2,
    contactDetails: ContactDetails? = null,
  ) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_SETUP_COMPLETED,
      uuid = offender.uuid,
      crn = offender.crn,
      description = "Practitioner completed setup for offender ${offender.crn}",
    )

    val details = contactDetails ?: ndiliusApiClient.getContactDetails(offender.crn)
    eventAuditService.recordSetupCompleted(offender, details)

    if (details != null) {
      try {
        val personalisation =
          mapOf(
            "name" to "${details.name.forename} ${details.name.surname}",
            "date" to offender.firstCheckin.format(DATE_FORMATTER),
            "frequency" to formatCheckinFrequency(CheckinInterval.fromDuration(offender.checkinInterval)),
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
        LOGGER.warn(
          "Failed to send setup completed notifications for offender {}: {}",
          offender.crn,
          sanitized,
        )
      }
    } else {
      LOGGER.warn(
        "Recording audit without contact details for offender {}: contact details not found",
        offender.crn,
      )
    }
  }

  /** Send notifications for checkin created event */
  fun sendCheckinCreatedNotifications(
    checkin: OffenderCheckinV2,
    contactDetails: ContactDetails,
  ) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_CREATED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "Check-in created for ${checkin.offender.crn} with due date ${checkin.dueDate}",
    )

    try {
      // Calculate final checkin date (last day offender can submit)
      val finalCheckinDate = checkin.dueDate.plus(checkinWindowPeriod).minusDays(1)

      val personalisation =
        mapOf(
          "firstName" to contactDetails.name.forename,
          "lastName" to contactDetails.name.surname,
          "date" to finalCheckinDate.format(DATE_FORMATTER),
          "url" to appConfig.checkinSubmitUrlV2(checkin.uuid).toString(),
        )

      // V1 only notifies offender for checkin invite (no practitioner template)
      val notificationsWithRecipients =
        notificationPersistence.buildOffenderNotifications(
          offender = checkin.offender,
          contactDetails = contactDetails,
          notificationType = NotificationType.OffenderCheckinInvite,
        )

      processAndSendNotifications(notificationsWithRecipients, personalisation)
    } catch (e: Exception) {
      val sanitized = PiiSanitizer.sanitizeException(e, checkin.offender.crn, checkin.offender.uuid)
      LOGGER.warn(
        "Failed to send checkin created notifications for checkin {}: {}",
        checkin.uuid,
        sanitized,
      )
      throw NotificationFailureException("Failed to send checkin created notification. Checkin uuid=${checkin.uuid}")
    }
  }

  /** Send notifications for checkin submitted event */
  fun sendCheckinSubmittedNotifications(
    checkin: OffenderCheckinV2,
    details: ContactDetails,
  ) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_SUBMITTED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "Check-in submitted for ${checkin.offender.crn}",
    )

    try {
      val checkinDto = checkin.dto(details)
      val flaggedResponses = checkinDto.flaggedResponses
      // Calculate flags (survey flags + 1 if auto ID check failed/missing)
      val surveyFlags = flaggedResponses.size
      val autoIdFailed = checkin.autoIdCheck == null ||
        checkin.autoIdCheck != AutomatedIdVerificationResult.MATCH
      val totalFlags = surveyFlags + if (autoIdFailed) 1 else 0
      // If "callback" is flagged, show the text. Otherwise, send empty string (hides the line).
      val contactRequestText = if (flaggedResponses.contains("callback")) {
        "This person has requested contact before their next appointment."
      } else {
        ""
      }
      // If id match fails, show the text. Otherwise, send empty string (hides the line).
      val autoIdFailedText = if (autoIdFailed) {
        "You need to review this check in to make sure the video shows the person who should be checking in."
      } else {
        ""
      }
      // Include all params needed by both offender and practitioner templates
      val personalisation =
        mapOf(
          "name" to "${details.name.forename} ${details.name.surname}",
          "practitionerName" to checkin.offender.practitionerId,
          "number" to totalFlags.toString(),
          "contactRequest" to contactRequestText,
          "autoIdFailed" to autoIdFailedText,
          "dashboardSubmissionUrl" to appConfig.checkinReviewUrlV2(checkin.uuid, checkin.offender.crn).toString(),
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
          contactDetails = details.practitioner,
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
    details: ContactDetails,
  ) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_REVIEWED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "Check-in reviewed for ${checkin.offender.crn} by ${checkin.reviewedBy}",
    )
  }

  /** Send notifications for checkin expired event */
  fun sendCheckinExpiredNotifications(
    checkin: OffenderCheckinV2,
    details: ContactDetails,
  ) {
    val personalisation =
      mapOf(
        "practitionerName" to checkin.offender.practitionerId,
        "name" to "${details.name.forename} ${details.name.surname}",
        "popDashboardUrl" to appConfig.checkinReviewUrlV2(checkin.uuid, checkin.offender.crn).toString(),
      )

    val notificationsWithRecipients =
      notificationPersistence.buildPractitionerNotifications(
        offender = checkin.offender,
        contactDetails = details.practitioner,
        checkin = checkin,
        notificationType = NotificationType.PractitionerCheckinMissed,
      )

    processAndSendNotifications(notificationsWithRecipients, personalisation)

    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_EXPIRED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "Check-in expired for ${checkin.offender.crn} (due date was ${checkin.dueDate})",
    )
  }

  /** Send notifications for checkin updated event */
  fun sendCheckinUpdatedNotifications(checkin: OffenderCheckinV2, annotation: OffenderEventLogV2) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_ANNOTATED,
      uuid = annotation.uuid,
      crn = checkin.offender.crn,
      description = "Check-in updated for ${checkin.offender.crn}",
    )
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
        LOGGER.warn(
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

    // Match V1 date format: "Monday 15 January 2025"
    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE d LLLL yyyy")

    /** Format checkin frequency - matches V1 RegistrationConfirmationMessage */
    private fun formatCheckinFrequency(interval: CheckinInterval): String = when (interval) {
      CheckinInterval.WEEKLY -> "week"
      CheckinInterval.TWO_WEEKS -> "two weeks"
      CheckinInterval.FOUR_WEEKS -> "four weeks"
      CheckinInterval.EIGHT_WEEKS -> "eight weeks"
    }
  }
}
