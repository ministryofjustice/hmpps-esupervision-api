package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.ProxyLinkCreator
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.appendQuestionsAndAnswers
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Service
class EventDetailV2Service(
  private val checkinRepository: OffenderCheckinV2Repository,
  private val eventLogRepository: OffenderEventLogV2Repository,
  private val proxyLinkCreator: ProxyLinkCreator,
  private val appConfig: AppConfig,
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

  private fun formatCheckinNotes(checkin: OffenderCheckinV2, eventType: DomainEventType, logEntry: IOffenderCheckinLogEntryV2Dto? = null): String {
    val sb = StringBuilder()
    when (eventType) {
      DomainEventType.V2_CHECKIN_CREATED -> {
        sb.appendLine("Check in created: ${formatHumanReadableDateTime(checkin.createdAt)}")
      }
      DomainEventType.V2_CHECKIN_SUBMITTED -> {
        sb.appendLine("Check in status: Submitted")
        sb.appendLine()
        checkin.autoIdCheck?.let {
          sb.appendLine("System ID check result: ${formatAutoIdCheckResult(it.name)}")
        }
        if (appConfig.enabledFeatures.contains(Feature.ESUP_1239)) {
          sb.appendLine("Reference photo: ${proxyLinkCreator.offenderReferencePhoto(checkin.offender)}")
          sb.appendLine("Checkin snapshot: ${proxyLinkCreator.checkinSnapshot(checkin, 0)}")
          sb.appendLine("Checkin video: ${proxyLinkCreator.checkinVideo(checkin)}")
        }
        checkin.surveyResponse?.let { survey ->
          sb.appendLine()
          sb.appendQuestionsAndAnswers(survey)
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
      -> {}
    }

    return sb.toString().trimEnd('\n')
  }

  private fun formatHumanReadableDateTime(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' h:mma", Locale.UK)
    return instant
      .atZone(ZoneId.of("Europe/London"))
      .format(formatter)
      .replace("AM", "am")
      .replace("PM", "pm")
  }

  private fun formatAutoIdCheckResult(result: String): String = when (result) {
    "MATCH" -> "Pass"
    "NO_MATCH" -> "Fail"
    "NO_FACE_DETECTED" -> "Fail"
    "ERROR" -> "Fail"
    else -> result
  }

  private fun formatManualIdCheckResult(result: String): String = when (result) {
    "MATCH", "CONFIRMED" -> "Yes"
    "NO_MATCH", "REJECTED" -> "No"
    else -> result
  }

  companion object {
    private val LOGGER = logger<EventDetailV2Service>()
  }
}
