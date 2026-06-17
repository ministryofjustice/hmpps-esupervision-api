package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.activeEventNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.AdditionalInformation
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.QuestionsReminderInfo
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
 * - EventAuditService for audit logging
 */
@Service
class NotificationOrchestratorService(
  private val notificationPersistence: NotificationPersistenceService,
  private val notifyGateway: NotifyGatewayService,
  private val domainEventService: DomainEventService,
  private val eventAuditService: EventAuditService,
  private val eventDetailService: EventDetailService,
  private val ndiliusApiClient: INdiliusApiClient,
  private val appConfig: AppConfig,
  private val clock: Clock,
  @param:Value("\${app.scheduling.checkin-notification.window:72h}") private val checkinWindow: Duration,
) {
  private val checkinWindowPeriod = Period.ofDays(checkinWindow.toDays().toInt())

  /** Send notifications for setup completed event */
  fun sendSetupCompletedNotifications(
    offender: Offender,
    contactDetails: ContactDetails? = null,
    setup: OffenderSetupDto,
  ) {
    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_SETUP_COMPLETED,
      uuid = offender.uuid,
      crn = offender.crn,
      description = "Practitioner completed setup for offender ${offender.crn}",
      additionalInformation = AdditionalInformation(
        eventNumber = contactDetails?.let { activeEventNumber(offender, it) },
        setupId = setup.setupId,
      ),
    )

    eventAuditService.recordSetupCompleted(offender, contactDetails, setup)

    if (contactDetails != null) {
      try {
        val personalisation =
          mapOf(
            "name" to "${contactDetails.name.forename} ${contactDetails.name.surname}",
            "date" to offender.firstCheckin.format(DATE_FORMATTER),
            "frequency" to formatCheckinFrequency(CheckinInterval.fromDuration(offender.checkinInterval)),
          )

        val notificationsWithRecipients =
          notificationPersistence.buildOffenderNotifications(
            offenderId = offender.id,
            crn = offender.crn,
            contactPreference = offender.contactPreference,
            contactDetails = contactDetails,
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

  /** Send notifications for reactivation completed event */
  fun sendReactivationCompletedNotifications(
    offender: Offender,
    contactDetails: ContactDetails? = null,
    setupId: UUID? = null,
  ) {
    val details = contactDetails ?: ndiliusApiClient.getContactDetails(offender.crn)

    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_SETUP_COMPLETED,
      uuid = offender.uuid,
      crn = offender.crn,
      description = "Practitioner reactivated online check-ins for offender ${offender.crn}",
      additionalInformation = AdditionalInformation(
        eventNumber = details?.let { activeEventNumber(offender, it) },
        setupId = setupId,
      ),
    )

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
            offenderId = offender.id,
            crn = offender.crn,
            contactPreference = offender.contactPreference,
            contactDetails = details,
            notificationType = NotificationType.OffenderCheckinsRestarted,
          )

        processAndSendNotifications(notificationsWithRecipients, personalisation)
      } catch (e: Exception) {
        val sanitized = PiiSanitizer.sanitizeException(e, offender.crn, offender.uuid)
        LOGGER.warn(
          "Failed to send reactivation setup completed notifications for offender {}: {}",
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

  /** Send notifications for deactivation completed event */
  fun sendDeactivationCompletedNotifications(
    offender: Offender,
    contactDetails: ContactDetails? = null,
    setupId: UUID? = null,
  ) {
    val details = contactDetails ?: ndiliusApiClient.getContactDetails(offender.crn)

    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_SETUP_REMOVED,
      uuid = offender.uuid,
      crn = offender.crn,
      description = "Online check-ins stopped for offender ${offender.crn}",
      additionalInformation = AdditionalInformation(
        eventNumber = details?.let { activeEventNumber(offender, it) },
        setupId = setupId,
      ),
    )

    if (details != null) {
      try {
        val personalisation =
          mapOf(
            "name" to "${details.name.forename} ${details.name.surname}",
          )

        val notificationsWithRecipients =
          notificationPersistence.buildOffenderNotifications(
            offenderId = offender.id,
            crn = offender.crn,
            contactPreference = offender.contactPreference,
            contactDetails = details,
            notificationType = NotificationType.OffenderCheckinsStopped,
          )

        processAndSendNotifications(notificationsWithRecipients, personalisation)
      } catch (e: Exception) {
        val sanitized = PiiSanitizer.sanitizeException(e, offender.crn, offender.uuid)
        LOGGER.warn(
          "Failed to send deactivation completed notifications for offender {}: {}",
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
  fun sendCheckinCreatedNotifications(event: CheckinCreatedEvent) {
    // NOTE: there's a bit of confusion about what happens when we don't have the offender's contact details.
    // In principle, we shouldn't get here in the first place (e.g., the service level code returns a 422 error
    // to the client). But let's say checkin was created, and we somehow got here. We will publish domain event
    // to reflect what happened in the database and possibly resolve any issues after investigating
    val checkin = event.checkin
    val activeEventNumber = if (checkin.personalDetails == null) event.currentEvent else activeEventNumber(event, checkin.personalDetails)
    if (activeEventNumber == null) {
      LOGGER.warn("Skipping checkin created notifications for checkin {}, CRN={}: no active events found", checkin.uuid, checkin.crn)
      return
    }

    event.publish(domainEventService)

    if (event.checkin.personalDetails == null) {
      LOGGER.warn("Skipping checkin created notifications for checkin {}, CRN={}: personal details not found", checkin.uuid, checkin.crn)
      return
    }

    try {
      // final checkin date == last day offender can submit
      val finalCheckinDate = checkin.dueDate.plus(checkinWindowPeriod).minusDays(1)
      val contactDetails = checkin.personalDetails
      val personalisation =
        mapOf(
          "firstName" to contactDetails.name.forename,
          "lastName" to contactDetails.name.surname,
          "date" to finalCheckinDate.format(DATE_FORMATTER),
          "url" to appConfig.checkinSubmitUrlV2(checkin.uuid).toString(),
        )

      // we only notify the offender by sending them a checkin invite
      val notificationsWithRecipients =
        notificationPersistence.buildOffenderNotifications(
          offenderId = event.offenderId,
          crn = checkin.crn,
          contactPreference = event.offenderContactPreference,
          contactDetails = contactDetails,
          notificationType = NotificationType.OffenderCheckinInvite,
        )

      processAndSendNotifications(notificationsWithRecipients, personalisation)
    } catch (e: Exception) {
      val sanitized = PiiSanitizer.sanitizeException(e, checkin.crn)
      LOGGER.warn(
        "Failed to send checkin created notifications for checkin {}: {}",
        checkin.uuid,
        sanitized,
      )
      throw NotificationFailureException("Failed to send checkin created notification. Checkin uuid=${checkin.uuid}")
    }
  }

  /** Send reminder notifications for checkin */
  fun sendReminderCheckinNotifications(
    checkin: OffenderCheckin,
    contactDetails: ContactDetails,
  ) {
    try {
      val personalisation =
        mapOf(
          "firstName" to contactDetails.name.forename,
          "lastName" to contactDetails.name.surname,
          "url" to appConfig.checkinSubmitUrlV2(checkin.uuid).toString(),
        )

      val notificationsWithRecipients =
        notificationPersistence.buildOffenderNotifications(
          offenderId = checkin.offender.id,
          crn = checkin.offender.crn,
          contactPreference = checkin.offender.contactPreference,
          contactDetails = contactDetails,
          notificationType = NotificationType.OffenderCheckinReminder,
        )

      processAndSendNotifications(notificationsWithRecipients, personalisation)
    } catch (e: Exception) {
      val sanitized = PiiSanitizer.sanitizeException(e, checkin.offender.crn, checkin.offender.uuid)
      LOGGER.warn(
        "Failed to send checkin REMINDER notifications for checkin {}: {}",
        checkin.uuid,
        sanitized,
      )
    }
  }

  /** Send notifications for checkin submitted event */
  fun sendCheckinSubmittedNotifications(event: CheckinSubmittedEvent) {
    event.publish(domainEventService)

    val checkin = event.checkin
    if (event.checkin.personalDetails == null) {
      LOGGER.debug("Skipping checkin submitted notifications for checkin {}: personal details not found", event.checkin.uuid)
      return
    }

    try {
      val flaggedResponses = checkin.flaggedResponses
      // Calculate flags (survey flags + 1 if auto ID check failed/missing)
      val surveyFlags = flaggedResponses.size
      val autoIdFailed = checkin.autoIdCheck == null ||
        checkin.autoIdCheck != AutomatedIdVerificationResult.MATCH
      val totalFlags = surveyFlags + if (autoIdFailed) 1 else 0
      // If "callback" is flagged, show the text within notify by sending 'yes'.
      val contactRequestFlag = if (flaggedResponses.contains("callback")) "yes" else "no"
      // Include all params needed by both offender and practitioner templates
      val personalisation = checkinSubmittedPersonalisationDetails(event.checkin.personalDetails, checkin, totalFlags, contactRequestFlag)

      val notificationsWithRecipients = mutableListOf<NotificationWithRecipient>()
      notificationsWithRecipients.addAll(
        notificationPersistence.buildOffenderNotifications(
          offenderId = event.offenderId,
          crn = event.checkin.crn,
          contactPreference = event.offenderContactPreference,
          contactDetails = event.checkin.personalDetails,
          notificationType = NotificationType.OffenderCheckinSubmitted,
        ),
      )
      notificationsWithRecipients.addAll(
        notificationPersistence.buildPractitionerNotifications(
          offenderId = event.offenderId,
          crn = checkin.crn,
          contactDetails = event.checkin.personalDetails.practitioner,
          checkin = checkin,
          notificationType = NotificationType.PractitionerCheckinSubmitted,
          practitionerId = event.practitionerId,
        ),
      )

      processAndSendNotifications(notificationsWithRecipients, personalisation)
    } catch (e: Exception) {
      val sanitized = PiiSanitizer.sanitizeException(e, checkin.crn, checkin.uuid)
      LOGGER.error(
        "Failed to send checkin submitted notifications for checkin {}: {}",
        checkin.uuid,
        sanitized,
      )
    }
  }

  fun checkinSubmittedPersonalisationDetails(
    details: ContactDetails,
    checkin: CheckinDto,
    totalFlags: Int,
    contactRequestFlag: String,
  ): Map<String, String> {
    require(contactRequestFlag == "yes" || contactRequestFlag == "no")

    val personalisation =
      mapOf(
        "name" to "${details.name.forename} ${details.name.surname}",
        "practitionerName" to (details.practitioner?.name?.forename ?: "Practitioner"),
        "number" to totalFlags.toString(),
        "contactRequestFlag" to contactRequestFlag,
        "dashboardSubmissionUrl" to appConfig.checkinReviewUrlV2(checkin.uuid, checkin.crn).toString(),
        "feedbackUrl" to appConfig.feedbackUrl().toString(),
      )
    return personalisation
  }

  /** Send notifications for checkin reviewed event */
  fun sendCheckinReviewedNotifications(event: CheckinReviewedEvent) = event.publish(domainEventService)

  /** Send notifications for checkin expired event */
  fun sendCheckinExpiredNotifications(
    checkin: OffenderCheckin,
    details: ContactDetails,
  ) {
    val personalisation = checkinExpiredPersonalisationDetails(details, checkin)

    val notificationsWithRecipients =
      notificationPersistence.buildPractitionerNotifications(
        offenderId = checkin.offender.id,
        crn = checkin.offender.crn,
        contactDetails = details.practitioner,
        checkin = checkin.dto(details, checkinWindow = checkinWindowPeriod, clock = clock),
        notificationType = NotificationType.PractitionerCheckinMissed,
        practitionerId = checkin.offender.practitionerId,
      )

    processAndSendNotifications(notificationsWithRecipients, personalisation)

    domainEventService.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_EXPIRED,
      uuid = checkin.uuid,
      crn = checkin.offender.crn,
      description = "Check-in expired for ${checkin.offender.crn} (due date was ${checkin.dueDate})",
    )
  }

  private fun checkinExpiredPersonalisationDetails(
    details: ContactDetails,
    checkin: OffenderCheckin,
  ): Map<String, String> = mapOf(
    "practitionerName" to (details.practitioner?.name?.forename ?: checkin.offender.practitionerId),
    "name" to "${details.name.forename} ${details.name.surname}",
    "popDashboardUrl" to appConfig.checkinReviewUrlV2(checkin.uuid, checkin.offender.crn).toString(),
  )

  /** Send notifications for checkin updated event */
  fun sendCheckinUpdatedNotifications(event: CheckinAnnotatedEvent) = event.publish(domainEventService)

  /** Send reminder for practitioner to add custom questions */
  fun sendPractitionerCustomQuestionsReminder(info: QuestionsReminderInfo) {
    LOGGER.info("Sending reminder to practitioner about custom questions: crn={}, practitioner={}", info.contactDetails.crn, info.practitionerId)
    val deadline = info.expectedCheckinDate.minusDays(1)

    val personalisation = mapOf(
      "offenderName" to "${info.contactDetails.name.forename}",
      "expectedCheckinDate" to info.expectedCheckinDate.format(QUESTIONS_REMINDER_FORMATTER),
      "questionsDeadline" to if (clock.today() == deadline) "today" else "on ${deadline.format(QUESTIONS_REMINDER_FORMATTER)}",
      "practitionerName" to (info.contactDetails.practitioner?.name?.forename ?: info.practitionerId),
      "dashboardUrl" to appConfig.addQuestionsUrl(info.offenderUuid, info.contactDetails.crn).toString(),
    )
    val notificationsWithRecipients = notificationPersistence.buildPractitionerNotifications(
      offenderId = null,
      crn = null,
      info.contactDetails.practitioner,
      null,
      NotificationType.PractitionerCustomQuestionsReminder,
      info.practitionerId,
    )

    processAndSendNotifications(notificationsWithRecipients, personalisation)
  }

  /** Get event detail for a given URL */
  fun getEventDetail(detailUrl: String): EventDetailResponse? = eventDetailService.getEventDetail(detailUrl)

  private fun processAndSendNotifications(
    notificationsWithRecipients: List<NotificationWithRecipient>,
    personalisation: Map<String, String>,
  ) {
    if (notificationsWithRecipients.isEmpty()) return

    notificationPersistence.saveNotifications(notificationsWithRecipients.map { it.notification })

    notificationsWithRecipients.forEach { wrapper ->
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
          wrapper.offender?.crn ?: "unknown",
          notifyId,
        )

        notificationPersistence.updateSingleNotificationStatus(
          notification = notification,
          success = true,
          notifyId = notifyId,
        )
      } catch (e: Exception) {
        val sanitized = PiiSanitizer.sanitizeException(e, wrapper.offender?.crn, null)
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
          error = PiiSanitizer.sanitizeMessage(e.message ?: "Unknown error", wrapper.offender?.crn, null),
        )
      }
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(NotificationOrchestratorService::class.java)

    // Match V1 date format: "Monday 15 January 2025"
    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE d LLLL yyyy")

    private val QUESTIONS_REMINDER_FORMATTER = DateTimeFormatter.ofPattern("d LLLL yyyy")

    /** Format checkin frequency - matches V1 RegistrationConfirmationMessage */
    private fun formatCheckinFrequency(interval: CheckinInterval): String = when (interval) {
      CheckinInterval.WEEKLY -> "week"
      CheckinInterval.TWO_WEEKS -> "two weeks"
      CheckinInterval.FOUR_WEEKS -> "four weeks"
      CheckinInterval.EIGHT_WEEKS -> "eight weeks"
    }
  }
}

fun CheckinCreatedEvent.publish(domainEventService: DomainEventService) {
  domainEventService.publishDomainEvent(
    eventType = DomainEventType.V2_CHECKIN_CREATED,
    uuid = checkin.uuid,
    crn = checkin.crn,
    description = "Check-in created for ${checkin.crn} with due date ${checkin.dueDate}",
  )
}

fun CheckinSubmittedEvent.publish(domainEventService: DomainEventService) {
  domainEventService.publishDomainEvent(
    eventType = DomainEventType.V2_CHECKIN_SUBMITTED,
    uuid = checkin.uuid,
    crn = checkin.crn,
    description = "Check-in submitted for ${checkin.crn}",
  )
}

fun CheckinReviewedEvent.publish(domainEventService: DomainEventService) {
  domainEventService.publishDomainEvent(
    eventType = DomainEventType.V2_CHECKIN_REVIEWED,
    uuid = checkin.uuid,
    crn = checkin.crn,
    description = "Check-in reviewed for ${checkin.crn} by ${checkin.reviewedBy}",
  )
}

fun CheckinAnnotatedEvent.publish(domainEventService: DomainEventService) {
  domainEventService.publishDomainEvent(
    eventType = DomainEventType.V2_CHECKIN_ANNOTATED,
    uuid = annotation.second,
    crn = checkin.crn,
    description = "Check-in updated for ${checkin.crn}",
  )
}
