package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Service
class EventDetailV2Service(
  private val offenderRepository: OffenderV2Repository,
  private val checkinRepository: OffenderCheckinV2Repository,
  private val eventLogRepository: OffenderEventLogV2Repository,
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
      DomainEventType.V2_SETUP_COMPLETED -> getRegistrationCompletedDetail(uuid)
      DomainEventType.V2_CHECKIN_CREATED,
      DomainEventType.V2_CHECKIN_SUBMITTED,
      DomainEventType.V2_CHECKIN_REVIEWED,
      DomainEventType.V2_CHECKIN_EXPIRED,
      -> getCheckinEventDetail(uuid, domainEventType)
      DomainEventType.V2_CHECKIN_ANNOTATED -> getCheckinAnnotatedEventDetail(uuid)
      null -> {
        LOGGER.warn("Unknown event type in detail URL: {}", detailUrl)
        null
      }
    }
  }

  private fun getRegistrationCompletedDetail(offenderUuid: UUID): EventDetailResponse? {
    val offender = offenderRepository.findByUuid(offenderUuid).orElse(null)
    if (offender == null) {
      LOGGER.warn("Offender not found for UUID: {}", offenderUuid)
      return null
    }

    val notes = formatSetupCompletedNotes(offender)

    val eventType = DomainEventType.V2_SETUP_COMPLETED
    return EventDetailResponse(
      eventReferenceId = "${eventType.eventTypeName}-$offenderUuid",
      eventType = eventType.eventTypeName,
      notes = notes,
      crn = offender.crn,
      offenderUuid = offenderUuid,
      checkinUuid = null,
      timestamp = offender.createdAt,
    )
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
    )
  }

  private fun formatSetupCompletedNotes(offender: OffenderV2): String {
    val lines = mutableListOf<String>()
    lines.add("Registration Completed")
    lines.add("Offender UUID: ${offender.uuid}")
    lines.add("CRN: ${offender.crn}")
    lines.add("Practitioner: ${offender.practitionerId}")
    lines.add("Status: ${offender.status}")
    lines.add("First check-in: ${offender.firstCheckin}")
    lines.add("Check-in interval: ${offender.checkinInterval}")
    lines.add("Created at: ${offender.createdAt}")
    lines.add("Created by: ${offender.createdBy}")
    return lines.joinToString("\n")
  }

  private fun formatCheckinNotes(checkin: OffenderCheckinV2, eventType: DomainEventType, logEntry: IOffenderCheckinLogEntryV2Dto? = null): String {
    val sb = StringBuilder()
    when (eventType) {
      DomainEventType.V2_SETUP_COMPLETED -> {
        sb.appendLine("Check in: ${formatHumanReadableDateTime(checkin.createdAt)}")
      }
      DomainEventType.V2_CHECKIN_CREATED -> {
        sb.appendLine("Check in created: ${formatHumanReadableDateTime(checkin.createdAt)}")
      }
      DomainEventType.V2_CHECKIN_SUBMITTED -> {
        sb.appendLine("Check in status: Submitted")
        sb.appendLine()
        checkin.autoIdCheck?.let {
          sb.appendLine("System ID check result: ${formatAutoIdCheckResult(it.name)}")
        }
        checkin.surveyResponse?.let { survey ->
          sb.appendLine()
          sb.appendLine("Check in answers:")
          formatSurveyResponseHumanReadable(survey).forEach {
            sb.appendLine(it)
            sb.appendLine()
          }
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

  /**
   * Format survey response in human-readable format
   */
  private fun formatSurveyResponseHumanReadable(survey: Map<String, Any>): List<String> {
    val lines = mutableListOf<String>()

    // Custom labels for known fields
    val customLabels = mapOf(
      "mentalHealth" to "How they have been feeling",
      "mentalHealthComment" to "What they want us to know about how they have been feeling",
      "assistance" to "Anything they need support with or to let us know",
      "mentalHealthSupport" to "What they want us to know about mental health",
      "alcoholSupport" to "What they want us to know about alcohol",
      "drugsSupport" to "What they want us to know about drugs",
      "moneySupport" to "What they want us to know about money",
      "housingSupport" to "What they want us to know about housing",
      "supportSystemSupport" to "What they want us to know about their support system",
      "otherSupport" to "What they want us to know about (something else)",
      "callback" to "If they need us to contact them before their next appointment",
      "callbackDetails" to "What they want to talk about",
    )

    for ((key, value) in customLabels) {
      val surveyValue = survey[key]

      // Skip null or empty values
      if (surveyValue == null) continue
      if (surveyValue is String && surveyValue.isBlank()) continue
      if (surveyValue is List<*> && surveyValue.isEmpty()) continue
      if (surveyValue is Map<*, *> && surveyValue.isEmpty()) continue

      // Format the value based on its type
      val formattedValue = formatValue(surveyValue)

      // Skip if formatted value is empty
      if (formattedValue.isBlank()) continue

      lines.add("$value: $formattedValue")
    }

    return lines
  }

  /**
   * Recursively format any value to human-readable string
   */
  @Suppress("UNCHECKED_CAST")
  private fun formatValue(value: Any?, indent: Int = 0): String {
    if (value == null) return ""

    return when (value) {
      is String -> formatStringValue(value)
      is Boolean -> if (value) "Yes" else "No"
      is Number -> value.toString()
      is List<*> -> formatListValue(value, indent)
      is Map<*, *> -> formatMapValue(value as Map<String, Any>, indent)
      else -> value.toString()
    }
  }

  /**
   * Format string values - convert enums and special values to readable text
   */
  private fun formatStringValue(value: String): String {
    if (value.isBlank()) return ""

    // Common enum patterns to human-readable
    return when {
      // Yes/No values
      value.equals("YES", ignoreCase = true) -> "Yes"
      value.equals("NO", ignoreCase = true) -> "No"
      // NO_HELP special case
      value == "NO_HELP" -> "No, I don't need any support"
      // SCREAMING_SNAKE_CASE to Title Case
      value == value.uppercase() -> {
        value.lowercase().split("_").joinToString(" ") { word ->
          word.replaceFirstChar { it.uppercase() }
        }
      }
      // Already readable
      else -> value
    }
  }

  /**
   * Format list values
   */
  private fun formatListValue(list: List<*>, indent: Int): String {
    val filteredList = list.filterNotNull()
      .map { formatValue(it, indent) }
      .filter { it.isNotBlank() && it != "No help" }

    if (filteredList.isEmpty()) return ""
    if (filteredList.size == 1) return filteredList.first()

    return filteredList.joinToString(", ")
  }

  /**
   * Format map/object values as key: value pairs
   */
  private fun formatMapValue(map: Map<String, Any>, indent: Int): String {
    if (map.isEmpty()) return ""

    val indentStr = "  ".repeat(indent + 1)
    val entries = map.entries
      .filter { (_, v) -> v != null }
      .mapNotNull { (k, v) ->
        val formattedValue = formatValue(v, indent + 1)
        if (formattedValue.isBlank()) {
          null
        } else {
          "$indentStr${camelCaseToHumanReadable(k)}: $formattedValue"
        }
      }

    if (entries.isEmpty()) return ""

    return "\n" + entries.joinToString("\n")
  }

  /**
   * Convert camelCase to Human Readable
   * e.g., "mentalHealthSupport" -> "Mental health support"
   */
  private fun camelCaseToHumanReadable(camelCase: String): String {
    if (camelCase.isBlank()) return ""

    val result = StringBuilder()
    for ((index, char) in camelCase.withIndex()) {
      if (char.isUpperCase() && index > 0) {
        result.append(' ')
        result.append(char.lowercase())
      } else if (index == 0) {
        result.append(char.uppercase())
      } else {
        result.append(char)
      }
    }
    return result.toString()
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(EventDetailV2Service::class.java)
  }
}
