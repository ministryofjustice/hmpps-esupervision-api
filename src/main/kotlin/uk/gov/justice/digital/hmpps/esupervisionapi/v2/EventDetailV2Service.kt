package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class EventDetailV2Service(
  private val offenderRepository: OffenderV2Repository,
  private val checkinRepository: OffenderCheckinV2Repository,
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

    return when (eventType) {
      "setup-completed" -> getRegistrationCompletedDetail(uuid)
      "checkin-created" -> getCheckinEventDetail(uuid, "CHECKIN_CREATED")
      "checkin-submitted" -> getCheckinEventDetail(uuid, "CHECKIN_SUBMITTED")
      "checkin-reviewed" -> getCheckinEventDetail(uuid, "CHECKIN_REVIEWED")
      "checkin-expired" -> getCheckinEventDetail(uuid, "CHECKIN_EXPIRED")
      else -> {
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

    return EventDetailResponse(
      eventReferenceId = "SETUP_COMPLETED-$offenderUuid",
      eventType = "SETUP_COMPLETED",
      notes = notes,
      crn = offender.crn,
      offenderUuid = offenderUuid,
      checkinUuid = null,
      timestamp = offender.createdAt,
    )
  }

  private fun getCheckinEventDetail(checkinUuid: UUID, eventType: String): EventDetailResponse? {
    val checkin = checkinRepository.findByUuid(checkinUuid).orElse(null)
    if (checkin == null) {
      LOGGER.warn("Checkin not found for UUID: {}", checkinUuid)
      return null
    }

    val notes = formatCheckinNotes(checkin, eventType)
    val timestamp = when (eventType) {
      "CHECKIN_SUBMITTED" -> checkin.submittedAt ?: checkin.createdAt
      "CHECKIN_REVIEWED" -> checkin.reviewedAt ?: checkin.createdAt
      else -> checkin.createdAt
    }

    return EventDetailResponse(
      eventReferenceId = "$eventType-$checkinUuid",
      eventType = eventType,
      notes = notes,
      crn = checkin.offender.crn,
      offenderUuid = checkin.offender.uuid,
      checkinUuid = checkinUuid,
      timestamp = timestamp,
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

  private fun formatCheckinNotes(checkin: OffenderCheckinV2, eventType: String): String {
    val lines = mutableListOf<String>()
    lines.add(formatEventTypeTitle(eventType))
    lines.add("Checkin UUID: ${checkin.uuid}")
    lines.add("CRN: ${checkin.offender.crn}")
    lines.add("Status: ${checkin.status}")
    lines.add("Due date: ${checkin.dueDate}")
    lines.add("Created at: ${checkin.createdAt}")
    checkin.checkinStartedAt?.let { lines.add("Checkin started at: $it") }
    checkin.submittedAt?.let { lines.add("Submitted at: $it") }
    checkin.autoIdCheck?.let { lines.add("Automated ID check: $it") }

    if (eventType == "CHECKIN_REVIEWED") {
      checkin.reviewedAt?.let { lines.add("Reviewed at: $it") }
      checkin.reviewedBy?.let { lines.add("Reviewed by: $it") }
      checkin.manualIdCheck?.let { lines.add("Manual ID check: $it") }
    }

    checkin.surveyResponse?.let { survey ->
      lines.add("Survey response: $survey")
      val flagged = computeFlaggedResponses(survey)
      if (flagged.isNotEmpty()) {
        lines.add("Flagged responses: ${flagged.joinToString(", ")}")
      }
    }

    return lines.joinToString("\n")
  }

  private fun computeFlaggedResponses(survey: Map<String, Any>): List<String> {
    val version = survey["version"] as? String ?: return emptyList()
    return when (version) {
      "2025-07-10@pilot" -> computeFlaggedFor20250710pilot(survey)
      else -> emptyList()
    }
  }

  private fun computeFlaggedFor20250710pilot(survey: Map<String, Any>): List<String> {
    val result = mutableListOf<String>()

    val mentalHealth = survey["mentalHealth"]
    if (mentalHealth == "NOT_GREAT" || mentalHealth == "STRUGGLING") {
      result.add("mentalHealth")
    }

    val assistance = survey["assistance"]
    val noAssistanceNeeded = listOf("NO_HELP")
    if (assistance != null && assistance != noAssistanceNeeded) {
      result.add("assistance")
    }

    val callback = survey["callback"]
    if (callback == "YES") {
      result.add("callback")
    }

    return result
  }

  private fun formatEventTypeTitle(eventType: String): String = eventType.split("_").joinToString(" ") { word ->
    word.lowercase().replaceFirstChar { it.uppercase() }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(EventDetailV2Service::class.java)
  }
}
