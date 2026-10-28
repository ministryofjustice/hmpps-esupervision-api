package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.config.SurveyValueExpansionsConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.ProxyLinkCreator
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.formatHumanReadableDateTime
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.appendQuestionsAndAnswers
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.DELIUS_ZONE
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.LivenessResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Service
class EventDetailService(
  private val checkinRepository: OffenderCheckinRepository,
  private val eventLogRepository: OffenderEventLogRepository,
  private val checkinDeliusEventRepository: CheckinDeliusEventRepository,
  private val proxyLinkCreator: ProxyLinkCreator,
  private val appConfig: AppConfig,
  val expansionsConfig: SurveyValueExpansionsConfig,
) {

  fun getEventDetail(detailUrl: String): EventDetailResponse? {
    // Support both relative (/v2/events/...) and absolute URLs (https://host/.../v2/events/...)
    val tail = detailUrl.substringAfter("/v2/events/", missingDelimiterValue = "")
    val parts = tail.split("/").filter { it.isNotEmpty() }

    if (parts.size < 2) {
      LOGGER.warn("Invalid detail URL format: {}", detailUrl)
      return null
    }

    val eventType = parts[0]
    val uuidStr = parts[1]
    val uuid = try {
      UUID.fromString(uuidStr)
    } catch (e: IllegalArgumentException) {
      LOGGER.warn("Invalid UUID in detail URL: {}", detailUrl)
      return null
    }

    return when (val domainEventType = DomainEventType.fromPath(eventType)) {
      DomainEventType.V2_CHECKIN_CREATED,
      DomainEventType.V2_CHECKIN_SUBMITTED,
      DomainEventType.V2_CHECKIN_REVIEWED,
      DomainEventType.V2_CHECKIN_EXPIRED,
      -> getCheckinEventDetail(uuid, domainEventType)
      DomainEventType.V2_CHECKIN_ANNOTATED -> getCheckinAnnotatedEventDetail(uuid)
      DomainEventType.V2_CHECKIN_SCHEDULED,
      DomainEventType.V2_CHECKIN_RESCHEDULED,
      DomainEventType.V2_CHECKIN_CANCELLED,
      DomainEventType.V2_CHECKIN_LINK_DELIVERED,
      DomainEventType.V2_CHECKIN_LINK_NOT_DELIVERED,
      -> getCheckinDeliusEventDetail(uuid, domainEventType)
      DomainEventType.V2_SETUP_COMPLETED,
      DomainEventType.V2_SETUP_REMOVED,
      null,
      -> {
        LOGGER.warn("Unknown event type in detail URL: {}", detailUrl)
        null
      }
    }
  }

  private fun getCheckinEventDetail(checkinUuid: UUID, eventType: DomainEventType): EventDetailResponse? {
    val checkin = checkinRepository.findByUuid(checkinUuid).orElse(null)
    if (checkin == null) {
      LOGGER.warn("Check in not found for UUID: {}", checkinUuid)
      return null
    }

    val notes = formatCheckinNotes(checkin, eventType)
    val timestamp = when (eventType) {
      DomainEventType.V2_CHECKIN_SUBMITTED -> checkin.submittedAt ?: checkin.createdAt
      DomainEventType.V2_CHECKIN_REVIEWED -> checkin.reviewedAt ?: checkin.createdAt
      DomainEventType.V2_CHECKIN_CREATED, DomainEventType.V2_CHECKIN_EXPIRED -> checkin.createdAt
      else -> checkin.createdAt
    }

    return EventDetailResponse(
      eventReferenceId = "${eventType.eventTypeName}-$checkinUuid",
      eventType = eventType.eventTypeName,
      notes = notes,
      crn = checkin.offender.crn,
      offenderUuid = checkin.offender.uuid,
      checkinUuid = checkinUuid,
      timestamp = timestamp,
      sensitive = checkin.sensitive,
      dateTime = if (eventType == DomainEventType.V2_CHECKIN_SUBMITTED && appConfig.enabledFeatures.contains(Feature.ESUP_2223)) {
        checkin.submittedAt?.atZone(DELIUS_ZONE)
      } else {
        null
      },
    )
  }

  /** The detail is the event as recorded, so it reflects the check-in at the time of the event. */
  private fun getCheckinDeliusEventDetail(eventId: UUID, eventType: DomainEventType): EventDetailResponse? {
    val event = checkinDeliusEventRepository.findByEventId(eventId).getOrNull()
    if (event == null || event.eventType != eventType) {
      LOGGER.warn("{} event not found for UUID: {}", eventType.eventTypeName, eventId)
      return null
    }
    val checkin = checkinRepository.findByUuid(event.checkinUuid).getOrNull()
    if (checkin == null) {
      LOGGER.warn("Check in not found for UUID={}, where {} event UUID={}", event.checkinUuid, eventType.eventTypeName, eventId)
      return null
    }

    return EventDetailResponse(
      eventReferenceId = "${eventType.eventTypeName}-$eventId",
      eventType = eventType.eventTypeName,
      notes = event.notes,
      crn = event.crn,
      offenderUuid = checkin.offender.uuid,
      checkinUuid = event.checkinUuid,
      timestamp = event.occurredAt,
      sensitive = checkin.sensitive,
      dateTime = event.dateTime?.atZone(DELIUS_ZONE),
    )
  }

  private fun getCheckinAnnotatedEventDetail(annotationUuid: UUID): EventDetailResponse? {
    val annotation = eventLogRepository.findCheckinLogByUuid(annotationUuid).getOrNull()
    if (annotation == null) {
      LOGGER.warn("Check in annotation not found for UUID: {}", annotationUuid)
      return null
    }
    val checkin = checkinRepository.findByUuid(annotation.checkin).orElse(null)
    if (checkin == null) {
      LOGGER.warn("Check in not found for UUID={}, where annotation UUID={}", annotation.checkin, annotationUuid)
    }

    val eventType = DomainEventType.V2_CHECKIN_ANNOTATED
    val notes = formatCheckinNotes(checkin, eventType, annotation)

    return EventDetailResponse(
      eventReferenceId = "${eventType.eventTypeName}-$annotationUuid",
      eventType = eventType.eventTypeName,
      notes = notes,
      crn = checkin.offender.crn,
      offenderUuid = checkin.offender.uuid,
      checkinUuid = annotation.checkin,
      timestamp = annotation.createdAt,
      sensitive = checkin.sensitive,
    )
  }

  private fun formatCheckinNotes(checkin: OffenderCheckin, eventType: DomainEventType, logEntry: IOffenderCheckinLogEntryDto? = null): String {
    val sb = StringBuilder()
    when (eventType) {
      DomainEventType.V2_CHECKIN_CREATED -> {
        sb.appendLine("Check in created: ${formatHumanReadableDateTime(checkin.createdAt)}")
      }
      DomainEventType.V2_CHECKIN_SUBMITTED -> {
        sb.appendLine("Check in status: Submitted")
        sb.appendLine()
        checkin.autoIdCheck?.let { autoIdCheck ->
          val label = if (checkin.livenessEnabled) "System ID and liveness check result" else "System ID check result"
          val passed = if (checkin.livenessEnabled) {
            autoIdCheck == AutomatedIdVerificationResult.MATCH && checkin.livenessResult == LivenessResult.LIVE
          } else {
            autoIdCheck == AutomatedIdVerificationResult.MATCH
          }
          sb.appendLine("$label: ${if (passed) "Pass" else "Fail"}")
        }
        if (appConfig.enabledFeatures.contains(Feature.ESUP_1239)) {
          sb.appendLine("Reference photo: ${proxyLinkCreator.offenderReferencePhoto(checkin.offender)}")
          sb.appendLine("Checkin snapshot: ${proxyLinkCreator.checkinSnapshot(checkin, 0)}")
        }
        checkin.surveyResponse?.let { survey ->
          sb.appendLine()
          sb.appendQuestionsAndAnswers(survey, expansionsConfig)
        }
      }
      DomainEventType.V2_CHECKIN_REVIEWED -> {
        sb.appendLine("Check in status: Reviewed")
        sb.appendLine()
        checkin.manualIdCheck?.let {
          sb.appendLine("Is the person in the video the correct person: ${formatManualIdCheckResult(it.name)}")
        }
        sb.appendLine()

        eventLogRepository.findAllCheckinEvents(checkin, setOf(LogEntryType.OFFENDER_CHECKIN_REVIEW_SUBMITTED)).lastOrNull()?.let {
          sb.appendLine("What action are you taking after reviewing this check in: ${it.notes}")
        }
      }
      DomainEventType.V2_CHECKIN_EXPIRED -> {
        sb.appendLine("Check in status: Missed")

        // Note: it's possible we get multiple log entries of that type as the "reviewCheckin" endpoint
        // might get concurrent requests (and they would write log entries, but only one of them would update the checkin)
        // We should ensure that does not happen, but if it does, let's return the last (query returns sorted by date asc)
        eventLogRepository.findAllCheckinEvents(checkin, setOf(LogEntryType.OFFENDER_CHECKIN_NOT_SUBMITTED)).lastOrNull()?.let {
          sb.appendLine()
          sb.appendLine("Why did they not complete their check in: ${it.notes}")
        }
      }
      DomainEventType.V2_CHECKIN_ANNOTATED -> {
        if (logEntry == null) {
          LOGGER.warn("Check in annotated event without log entry: {} of type {}", checkin.uuid, eventType)
        } else {
          sb.appendLine("${logEntry.notes}")
        }
      }
      DomainEventType.V2_SETUP_COMPLETED,
      DomainEventType.V2_SETUP_REMOVED,
      DomainEventType.V2_CHECKIN_SCHEDULED,
      DomainEventType.V2_CHECKIN_RESCHEDULED,
      DomainEventType.V2_CHECKIN_CANCELLED,
      DomainEventType.V2_CHECKIN_LINK_DELIVERED,
      DomainEventType.V2_CHECKIN_LINK_NOT_DELIVERED,
      -> {}
    }

    return sb.toString().trimEnd('\n')
  }

  private fun formatManualIdCheckResult(result: String): String = when (result) {
    "MATCH", "CONFIRMED" -> "Yes, and there’s nothing concerning"
    "MATCH_WITH_CONCERN" -> "Yes, and there’s visible concern"
    "NO_MATCH", "REJECTED" -> "No, it is not the person"
    else -> result
  }

  companion object {
    private val LOGGER = logger<EventDetailService>()
  }
}
