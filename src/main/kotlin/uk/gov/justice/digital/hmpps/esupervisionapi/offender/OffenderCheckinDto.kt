package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import java.net.URL
import java.time.Instant
import java.util.UUID

enum class CheckinStatus {
  CREATED,
  SUBMITTED,
  REVIEWED,
  CANCELLED,
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
  val dueDate: Instant,
  val offender: OffenderDto,
  val submittedOn: Instant?,
  val surveyResponse: SurveyContents?,
  val createdBy: String,
  val createdAt: Instant,
  val reviewedBy: String?,
  /**
   * Will be set to pre-signed S3 URL
   */
  val videoUrl: URL?,
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

/**
 * Holds data submitted as a checkin by the offender.
 */
data class OffenderCheckinSubmission(
  val offender: UUID,
  val survey: SurveyContents,
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
