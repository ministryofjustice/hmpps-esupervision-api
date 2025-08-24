package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import java.net.URL
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class CheckinStatus {
  CREATED,
  SUBMITTED,
  REVIEWED,
  CANCELLED,
  EXPIRED,
  ;

  fun canTransitionTo(newStatus: CheckinStatus): Boolean = when (this) {
    CREATED -> newStatus == SUBMITTED || newStatus == CANCELLED || newStatus == EXPIRED
    SUBMITTED -> newStatus == REVIEWED || newStatus == CANCELLED
    REVIEWED -> false
    CANCELLED -> false
    EXPIRED -> false
  }
}

/**
 *
 */
enum class AutomatedIdVerificationResult {
  MATCH,
  NO_MATCH,
}

enum class ManualIdVerificationResult {
  MATCH,
  NO_MATCH,
}

typealias SurveyContents = Map<String, Object>

data class OffenderCheckinDto(
  val uuid: UUID,
  val status: CheckinStatus,
  val dueDate: LocalDate,
  val offender: OffenderDto,
  val submittedAt: Instant?,
  val surveyResponse: SurveyContents?,
  val createdBy: ExternalUserId,
  val createdAt: Instant,
  val reviewedBy: ExternalUserId?,
  val reviewedAt: Instant?,
  /**
   * Will be set to pre-signed S3 URL
   */
  val videoUrl: URL?,
  val snapshotUrl: URL?,
  val autoIdCheck: AutomatedIdVerificationResult?,
  val manualIdCheck: ManualIdVerificationResult?,
) {

  @get:JsonProperty("flaggedResponses")
  val flaggedResponses: List<String>
    @Schema(description = "Flagged keys of the survey")
    get() {
      if (surveyResponse == null) {
        return emptyList()
      }

      val version = surveyResponse.get("version")
      if (version != null) {
        val fn = versionToFlaggingFn.get(version as String)
        if (fn != null) {
          return fn(surveyResponse)
        }
      }

      return emptyList()
    }
}

enum class OffenderCheckinLogsHint {
  ALL,
  SUBSET,
  OMITTED,
}

data class OffenderCheckinLogs(
  /**
   * A hint to the client on whether the returned
   * collection has all logs or
   */
  val hint: OffenderCheckinLogsHint,
  val logs: List<IOffenderCheckinEventLogDto>,
)

data class OffenderCheckinResponse(
  val checkin: OffenderCheckinDto,
  val checkinLogs: OffenderCheckinLogs,
)

/**
 * Holds data submitted as a checkin by the offender.
 */
data class OffenderCheckinSubmission(
  val offender: UUID,
  val survey: SurveyContents,
)

data class AutomatedVerificationResult(
  val result: AutomatedIdVerificationResult,
)

/**
 * Maps survey versions to flagging functions.
 */
private val versionToFlaggingFn = mapOf<String, (SurveyContents) -> List<String>>(
  "2025-07-10@pilot" to { flaggedFor20250710pilot(it) },
)

fun flaggedFor20250710pilot(survey: SurveyContents): List<String> {
  val result = mutableListOf<String>()

  val mentalHealth = survey.get("mentalHealth")
  if (mentalHealth == "NOT_GREAT" || mentalHealth == "STRUGGLING") {
    result.add("mentalHealth")
  }

  val assistance = survey.get("assistance")
  val noAssistanceNeeded = listOf("NO_HELP")
  if (assistance != null && assistance != noAssistanceNeeded) {
    result.add("assistance")
  }

  val callback = survey.get("callback")
  if (callback == "YES") {
    result.add("callback")
  }

  return result.toList()
}

enum class NotificationContextType {
  SCHEDULED_JOB,
  SINGLE,
}

sealed class NotificationContext(
  val reference: String,
  /**
   * Answers *why* are we sending the notification
   */
  val type: NotificationContextType,
)

/**
 * To be used for bulk notifications (e.g., in a scheduled job, so that we can link
 * are notifications to that job).
 */
data class BulkNotificationContext(val ref: String) : NotificationContext(ref, NotificationContextType.SCHEDULED_JOB)

/**
 * To be used for one-off notification.
 */
data class SingleNotificationContext(val ref: String) : NotificationContext(ref, NotificationContextType.SINGLE) {

  companion object {
    fun from(notificationId: UUID) = SingleNotificationContext("SNGL-$notificationId")

    fun forCheckin(now: LocalDate) = SingleNotificationContext("CHK-${now.format(DateTimeFormatter.ISO_LOCAL_DATE)}")

    fun forCheckin(clock: Clock) = forCheckin(clock.instant().atZone(clock.zone).toLocalDate())
  }
}

data class NotificationResultSummary(
  val notificationId: UUID,
  val context: NotificationContext,
  val timestamp: ZonedDateTime,
  val status: String?,
  val error: String?,
)

/**
 * NOTE: stored in as JSON in the DB
 */
data class NotificationResults(
  val results: List<NotificationResultSummary>,
)
