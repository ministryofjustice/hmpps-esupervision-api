package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.LivenessResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.serialization.LocalDateDeserializer
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.question.ValidQuestionParams
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ========================================
// Delius API DTOs (matching OpenAPI spec)
// ========================================

data class CodedDescription(val code: String, val description: String)

data class Event(
  val number: Long,
  val mainOffence: CodedDescription,
  val sentence: Sentence?,
) {
  data class Sentence(
    @field:JsonDeserialize(using = LocalDateDeserializer::class)
    val date: LocalDate,

    val description: String,
  )
}

/** Contact details from Delius API
 *
 * See https://github.com/ministryofjustice/hmpps-probation-integration-services/blob/main/projects/esupervision-and-delius/src/main/kotlin/uk/gov/justice/digital/hmpps/model/ContactDetails.kt
 * */
data class ContactDetails(
  @field:Schema(description = "Case Reference Number", required = true, example = "X123456")
  val crn: String,

  @field:Schema(description = "Person's name", required = true)
  val name: Name,

  @field:Schema(
    description = "Mobile phone number (optional)",
    required = false,
    example = "07700900123",
  )
  val mobile: String? = null,

  @field:Schema(
    description = "Email address (optional)",
    required = false,
    example = "john.smith@example.com",
  )
  val email: String? = null,

  @field:Schema(description = "Practitioner details (optional)", required = false)
  val practitioner: PractitionerDetails? = null,

  /**
   * Note: no active events mean that the offender has no sentences at this moment.
   */
  @field:Schema(
    description = "Collection of active events.",
    required = false,
  )
  val events: List<Event>? = null,
)

/** Person's name from Ndilius */
data class Name(
  @Schema(description = "Forename", required = true, example = "John") val forename: String,
  @Schema(description = "Surname", required = true, example = "Smith") val surname: String,
)

/** Practitioner details from Ndilius API */
data class PractitionerDetails(
  @Schema(description = "Practitioner's name", required = true) val name: Name,
  @Schema(
    description = "Practitioner's email address (optional - may not be available)",
    required = false,
    example = "practitioner@example.com",
  )
  val email: String? = null,
  @Schema(description = "Local Admin Unit", required = false)
  val localAdminUnit: OrganizationalUnit? = null,
  @Schema(description = "Probation Delivery Unit", required = false)
  val probationDeliveryUnit: OrganizationalUnit? = null,
  @Schema(description = "Provider", required = false)
  val provider: OrganizationalUnit? = null,
)

/** Organizational unit (LAU, PDU, Provider) */
data class OrganizationalUnit(
  @Schema(description = "Unit code", required = true, example = "N01ABC") val code: String,
  @Schema(description = "Unit description", required = false, example = "London North LAU")
  val description: String? = null,
)

/** Personal details for identity validation */
data class PersonalDetails(
  @Schema(description = "Case Reference Number", required = true, example = "X123456")
  @field:NotBlank
  @field:Pattern(regexp = "^[A-Z]\\d{6}$", message = "CRN must be in format X123456")
  val crn: String,
  @Schema(description = "Person's name", required = true) val name: Name,
  @Schema(description = "Date of birth", required = true, example = "1985-05-14")
  @JsonDeserialize(using = LocalDateDeserializer::class)
  val dateOfBirth: LocalDate,
)

/** Identity validation response */
data class IdentityValidationResponse(
  @Schema(description = "Whether identity was verified", required = true)
  val verified: Boolean,
  @Schema(description = "Error message if validation failed", required = false)
  val error: String? = null,
)

/** Facial recognition verification result */
data class FacialRecognitionResult(
  @Schema(description = "Verification result: MATCH, NO_MATCH, NO_FACE_DETECTED, or ERROR", required = true)
  val result: AutomatedIdVerificationResult,
)

/** Liveness session creation response */
data class LivenessSessionResponse(
  @Schema(description = "AWS Rekognition liveness session ID", required = true)
  val sessionId: String,
)

/** Liveness verification request */
data class LivenessVerifyRequest(
  @Schema(description = "Liveness session ID to verify", required = true)
  val sessionId: String,
)

/** Liveness verification response */
data class LivenessVerificationResponse(
  @Schema(description = "Whether the session passed liveness detection", required = true)
  val isLive: Boolean,
  @Schema(description = "Liveness confidence score (0-100)", required = true)
  val livenessConfidence: Float,
  @Schema(description = "Face comparison result against setup photo", required = true)
  val result: AutomatedIdVerificationResult,
)

// ========================================
// V2 Offender DTOs
// ========================================

/** V2 Offender DTO (no PII, only CRN) */
data class OffenderV2Dto(
  @field:Schema(description = "Unique identifier", required = true) val uuid: UUID,
  @field:Schema(description = "Case Reference Number", required = true, example = "X123456")
  val crn: String,
  @field:Schema(description = "Practitioner ID", required = true)
  val practitionerId: ExternalUserId,
  @field:Schema(description = "Offender status", required = true) val status: OffenderStatus,
  @field:Schema(description = "Date of first checkin", required = true)
  @field:JsonDeserialize(using = LocalDateDeserializer::class)
  val firstCheckin: LocalDate,
  @field:Schema(description = "Interval between checkins", required = true)
  val checkinInterval: CheckinInterval,
  @field:Schema(description = "Created timestamp", required = true) val createdAt: Instant,
  @field:Schema(description = "Created by practitioner ID", required = true) val createdBy: String,
  @field:Schema(description = "Last updated timestamp", required = true) val updatedAt: Instant,
  @field:Schema(description = "Contact preference - phone or email", required = true)
  val contactPreference: ContactPreference,
  @field:Schema(description = "Personal details from Ndilius (optional)", required = false)
  val personalDetails: ContactDetails? = null,
)

/** V2 Offender creation request */
data class CreateOffenderV2Request(
  @Schema(description = "Case Reference Number", required = true, example = "X123456")
  @field:NotBlank
  @field:Pattern(regexp = "^[A-Z]\\d{6}$", message = "CRN must be in format X123456")
  val crn: String,
  @Schema(description = "Practitioner ID", required = true)
  @field:NotBlank
  val practitionerId: ExternalUserId,
  @Schema(description = "Date of first checkin", required = true)
  @JsonDeserialize(using = LocalDateDeserializer::class)
  val firstCheckin: LocalDate,
  @Schema(description = "Interval between checkins", required = true)
  val checkinInterval: CheckinInterval,
)

// ========================================
// V2 Offender Setup DTOs
// ========================================

/** V2 Offender information for starting setup V2 does not store PII - only CRN */
data class OffenderInfoV2(
  @Schema(description = "Setup UUID", required = true) val setupUuid: UUID,
  @Schema(description = "Practitioner ID", required = true)
  @field:NotBlank
  val practitionerId: ExternalUserId,
  @Schema(description = "Case Reference Number", required = true, example = "X123456")
  @field:NotBlank
  @field:Pattern(regexp = "^[A-Z]\\d{6}$", message = "CRN must be in format X123456")
  val crn: String,
  @Schema(description = "Date of first checkin", required = true)
  @JsonDeserialize(using = LocalDateDeserializer::class)
  val firstCheckin: LocalDate,
  @Schema(description = "Interval between checkins", required = true)
  val checkinInterval: CheckinInterval,
  @Schema(description = "POP contact preference", required = true)
  val contactPreference: ContactPreference,
  @Schema(description = "Setup start timestamp (optional)", required = false)
  val startedAt: Instant? = null,
)

/**
 * Offender information required to start/resume the offender setup process.
 */
data class OffenderInfoInitial(
  @field:Schema(description = "Practitioner ID", required = true)
  @field:NotBlank
  val practitionerId: ExternalUserId,
  @field:Schema(description = "Case Reference Number", required = true, example = "X123456")
  @field:NotBlank
  @field:Pattern(regexp = "^[A-Z]\\d{6}$", message = "CRN must be in format X123456")
  val crn: String,
  @field:Schema(description = "Date of first checkin", required = true)
  @field:JsonDeserialize(using = LocalDateDeserializer::class)
  val firstCheckin: LocalDate,
  @field:Schema(description = "Interval between checkins", required = true)
  val checkinInterval: CheckinInterval,
  @field:Schema(description = "POP contact preference", required = true)
  val contactPreference: ContactPreference,
  @field:Schema(description = "Setup start timestamp (optional)", required = false)
  val startedAt: Instant? = null,
)

/** V2 Offender setup DTO (response) */
data class OffenderSetupV2Dto(
  @Schema(description = "Setup unique identifier", required = true) val uuid: UUID,
  @Schema(description = "Practitioner's unique ID", required = true)
  val practitionerId: ExternalUserId,
  @Schema(description = "Offender's unique ID", required = true) val offenderUuid: UUID,
  @Schema(description = "Created timestamp", required = true) val createdAt: Instant,
  @Schema(description = "Setup started timestamp (optional)", required = false)
  val startedAt: Instant? = null,
)

// ========================================
// V2 Checkin DTOs
// ========================================

/** V2 Checkin status */
enum class CheckinV2Status {
  CREATED,
  SUBMITTED,
  REVIEWED,
  EXPIRED,
  CANCELLED,
  ;

  fun canTransitionTo(newStatus: CheckinV2Status): Boolean = when (this) {
    CREATED -> newStatus == SUBMITTED || newStatus == CANCELLED || newStatus == EXPIRED
    SUBMITTED -> newStatus == REVIEWED || newStatus == CANCELLED
    REVIEWED -> false
    CANCELLED -> false
    EXPIRED -> false
  }
}

enum class SurveyVersion(val version: String) {
  V20250710pilot("2025-07-10@pilot"),

  /**
   * the one below was added to the UI repo with incorrect date formatting. Keeping it here in the mapping in case there are entries in dev that use the incorrect date.
   */
  V20260707Typo("2026-0-07@pre"),
  V20260707Pre("2026-01-07@pre"),

  /**
   * the first release of custom questions will have a mix of previous survey format + custom questions,
   * so we can use the existing flagging logic (flagging for custom questions is not in scope)
   */
  V20260416Questions("2026-04-16@questions"),
}

/** V2 Checkin DTO */
data class CheckinV2Dto(
  @field:Schema(description = "Unique identifier", required = true) val uuid: UUID,
  @field:Schema(description = "Case Reference Number", required = true) val crn: String,
  @field:Schema(description = "Checkin status", required = true) val status: CheckinV2Status,
  @field:Schema(description = "Due date", required = true)
  @field:JsonDeserialize(using = LocalDateDeserializer::class)
  val dueDate: LocalDate,
  @field:Schema(description = "Created timestamp", required = true) val createdAt: Instant,
  @field:Schema(description = "Created by", required = true) val createdBy: String,
  @field:Schema(description = "Submitted timestamp", required = false)
  val submittedAt: Instant? = null,
  @field:Schema(description = "Review started by", required = false)
  val reviewedAt: Instant? = null,
  @field:Schema(description = "Reviewed by", required = false) val reviewedBy: String? = null,
  @field:Schema(description = "Checkin started timestamp", required = false)
  val checkinStartedAt: Instant? = null,
  @field:Schema(description = "Auto ID check result", required = false)
  val autoIdCheck: AutomatedIdVerificationResult? = null,
  @field:Schema(description = "Liveness check result (null for video-based check-ins)", required = false)
  val livenessResult: LivenessResult? = null,
  @field:Schema(description = "Liveness confidence score 0-100 (null for video-based check-ins)", required = false)
  val livenessConfidence: Float? = null,
  @field:Schema(description = "Whether liveness verification was enabled for this check-in", required = false)
  val livenessEnabled: Boolean = false,
  @field:Schema(description = "Manual ID check result", required = false)
  val manualIdCheck: ManualIdVerificationResult? = null,
  @field:Schema(description = "Survey responses (JSONB)", required = false)
  val surveyResponse: Map<String, Any>? = null,
  @field:Schema(description = "Personal details from Ndilius (optional)", required = false)
  val personalDetails: ContactDetails? = null,
  @field:Schema(description = "Presigned S3 URL for video playback", required = false)
  val videoUrl: URL? = null,
  @field:Schema(description = "Presigned S3 URL for snapshot image from the video", required = false)
  val snapshotUrl: URL? = null,
  @field:Schema(description = "Risk management feedback", required = false)
  val riskFeedback: Boolean? = null,
  @field:Schema(description = "Whether the review/annotation contains sensitive information", required = false)
  val sensitive: Boolean = false,
  @field:Schema(description = "Checkin logs with practitioner notes", required = true)
  val checkinLogs: CheckinLogsV2Dto,
  @field:Schema(description = "Presigned S3 URL for reference photo", required = false)
  val photoUrl: URL? = null,
  @field:Schema(description = "Notes of further actions to be taken after checkin review", required = false)
  val furtherActions: String? = null,
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

private typealias SurveyContents = Map<String, Any>

/**
 * Maps survey versions to flagging functions.
 * Add new survey versions here as they are created.
 */
private val versionToFlaggingFn = mapOf<String, (SurveyContents) -> List<String>>(
  SurveyVersion.V20250710pilot.version to { flaggedFor20250710pilot(it) },
  SurveyVersion.V20260707Typo.version to { flaggedFor20250710pilot(it) },
  SurveyVersion.V20260707Pre.version to { flaggedFor20250710pilot(it) },
  SurveyVersion.V20260416Questions.version to { flaggedFor20250710pilot(it) },
)

/**
 * Flagging logic for the pre-pilot and pilot version of survey
 */
private fun flaggedFor20250710pilot(survey: SurveyContents): List<String> {
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

/** Submit checkin request */
data class SubmitCheckinV2Request(
  @Schema(description = "Survey responses", required = true) val survey: Map<String, Any>,
)

/** Review checkin request */
data class ReviewCheckinV2Request(
  @Schema(description = "Reviewed by practitioner ID", required = true)
  @field:NotBlank
  val reviewedBy: ExternalUserId,
  @Schema(description = "Manual ID check result", required = false)
  val manualIdCheck: ManualIdVerificationResult? = null,
  @Schema(description = "Review notes", required = false)
  val notes: String? = null,
  @Schema(description = "Missed checkin comments", required = false)
  val missedCheckinComment: String? = null,
  @Schema(description = "Risk management feedback", required = false)
  val riskManagementFeedback: Boolean? = null,
  @Schema(description = "Whether the review contains sensitive information", required = false)
  val sensitive: Boolean = false,
)

/** Review started request */
data class ReviewStartedRequest(
  @Schema(description = "Practitioner ID who started review", required = true)
  @field:NotBlank
  val practitionerId: ExternalUserId,
)

/** Annotate checkin request */
data class AnnotateCheckinV2Request(
  @Schema(description = "Updated by practitioner ID", required = true)
  @field:NotBlank
  val updatedBy: ExternalUserId,
  @Schema(description = "Notes about the checkin", required = true)
  @field:NotBlank
  val notes: String,
  @Schema(description = "Whether the annotation contains sensitive information", required = false)
  val sensitive: Boolean = false,
)

/** Create checkin request (DEBUG ONLY) */
data class CreateCheckinV2Request(
  @Schema(description = "Practitioner ID", required = true)
  @field:NotBlank
  val practitioner: ExternalUserId,
  @Schema(description = "Offender UUID", required = true)
  val offender: UUID,
  @Schema(description = "Due date", required = true)
  @JsonDeserialize(using = LocalDateDeserializer::class)
  val dueDate: LocalDate,
)

/** Create checkin by crn request (DEBUG ONLY) */
data class CreateCheckinByCrnV2Request(
  @Schema(description = "Practitioner ID", required = true)
  @field:NotBlank
  val practitioner: ExternalUserId,
  @Schema(description = "Offender CRN", required = true)
  val offender: String,
  @Schema(description = "Due date", required = true)
  @JsonDeserialize(using = LocalDateDeserializer::class)
  val dueDate: LocalDate,
)

/** Checkin notification request (DEBUG ONLY) */
data class CheckinNotificationV2Request(
  @Schema(description = "Practitioner ID", required = true)
  @field:NotBlank
  val practitioner: ExternalUserId,
)

// ========================================
// Checkin List DTOs
// ========================================

/** Use case hint for listing checkins (corresponds to MPOP UI tabs) */
enum class CheckinListUseCaseV2 {
  /** Checkins needing attention (SUBMITTED or EXPIRED without review) */
  NEEDS_ATTENTION,

  /** Reviewed checkins (REVIEWED or EXPIRED with review) */
  REVIEWED,

  /** Checkins awaiting offender submission (CREATED) */
  AWAITING_CHECKIN,
}

/** Pagination metadata */
data class PaginationV2(
  @Schema(description = "Current page number (zero-indexed)", required = true, example = "0")
  val pageNumber: Int,
  @Schema(description = "Page size", required = true, example = "20")
  val pageSize: Int,
)

/** Paginated collection response */
data class CheckinCollectionV2Response(
  @Schema(description = "Pagination metadata", required = true)
  val pagination: PaginationV2,
  @Schema(description = "Checkin items", required = true)
  val content: List<CheckinV2Dto>,
)

// ========================================
// Checkin Event Logging DTOs
// ========================================

/** Checkin event types for audit logging */
enum class CheckinEventTypeV2 {
  /** Attempt to access checkin from outside the UK */
  CHECKIN_OUTSIDE_ACCESS,
}

/** Log checkin event request */
data class LogCheckinEventV2Request(
  @Schema(description = "Event type", required = true)
  val eventType: CheckinEventTypeV2,
  @Schema(description = "Optional comment or context", required = false)
  val comment: String? = null,
)

// ========================================
// Upload Location DTOs
// ========================================

/** Upload location for video/snapshots */
data class UploadLocation(
  @Schema(description = "Presigned S3 URL", required = true) val url: URL,
  @Schema(description = "Content type", required = true) val contentType: String,
  @Schema(
    description = "Time to live (ISO 8601 duration)",
    required = true,
    example = "PT10M",
  )
  val ttl: String,
)

/** Upload locations response */
data class UploadLocationsV2Response(
  @Schema(description = "Video upload location", required = true) val video: UploadLocation,
  @Schema(description = "Snapshot upload locations", required = true)
  val snapshots: List<UploadLocation>,
)

// ========================================
// Offender Event Log DTOs
// ========================================

/** Hint indicating completeness of the logs collection */
enum class CheckinLogsHintV2 {
  /** All logs are included */
  ALL,

  /** Only a subset of logs is included */
  SUBSET,

  /** Logs are omitted from the response */
  OMITTED,
}

/** Log entry type for V2 checkins - matches EventAuditV2 event types */
enum class CheckinLogEntryTypeV2

interface IOffenderLogEntryV2Dto {
  val uuid: UUID
  val notes: String
  val createdAt: Instant
  val logEntryType: LogEntryType
  val practitioner: String
  // val offender: UUID
}

interface IOffenderCheckinReferenceV2 {
  val checkin: UUID
}

/**
 * If we're interested in checkin events specifically,
 * we can ensure the specific type is used.
 */
interface IOffenderCheckinLogEntryV2Dto :
  IOffenderLogEntryV2Dto,
  IOffenderCheckinReferenceV2

data class OffenderCheckinLogEntryV2Dto(
  @field:Schema(description = "Event UUID") override val uuid: UUID,
  @field:Schema(description = "Notes/comment", required = false) override val notes: String,
  @field:Schema(description = "Occurred timestamp", required = true) override val createdAt: Instant,
  @field:Schema(description = "Log entry type", required = true) override val logEntryType: LogEntryType,
  @field:Schema(description = "Practitioner ID who created the log", required = false)override val practitioner: String,
  // @field:Schema(description = "Offender UUID") override val offender: UUID,
  @field:Schema(description = "Checkin UUID", required = true) override val checkin: UUID,
) : IOffenderCheckinLogEntryV2Dto

/** Wrapper for checkin logs with hint about completeness */
data class CheckinLogsV2Dto(
  @Schema(description = "Hint about whether all logs are included", required = true)
  val hint: CheckinLogsHintV2,
  @Schema(description = "List of log entries", required = true)
  val logs: List<IOffenderCheckinLogEntryV2Dto>,
)

// ========================================
// Event DTOs
// ========================================

/** SQS event message to Ndilius */
data class CheckinEventMessage(
  @Schema(description = "Event type", required = true) val eventType: String,
  @Schema(description = "Case Reference Number", required = true) val crn: String,
  @Schema(description = "Checkin UUID", required = false) val checkinUuid: UUID? = null,
  @Schema(description = "Offender UUID", required = false) val offenderUuid: UUID? = null,
  @Schema(description = "Due date (for CHECKIN_CREATED)", required = false)
  @JsonDeserialize(using = LocalDateDeserializer::class)
  val dueDate: LocalDate? = null,
  @Schema(description = "Callback URL for Ndilius to query event details", required = true)
  val callbackUrl: String,
  @Schema(description = "Timestamp", required = true) val timestamp: Instant,
)

/** Event detail response (for Ndilius callback) */
data class EventDetailResponse(
  @Schema(description = "Event reference ID", required = true) val eventReferenceId: String,
  @Schema(description = "Event type", required = true) val eventType: String,
  @Schema(description = "Formatted notes", required = true) val notes: String,
  @Schema(description = "Case Reference Number", required = true) val crn: String,
  @Schema(description = "Checkin UUID", required = false) val checkinUuid: UUID? = null,
  @Schema(description = "Offender UUID", required = false) val offenderUuid: UUID? = null,
  @Schema(description = "Timestamp", required = true) val timestamp: Instant,
  @Schema(description = "Sensitive", required = false) val sensitive: Boolean = false,
)

enum class QuestionPolicy {
  FIXED,
  CUSTOMISABLE,
  ;

  companion object {
    fun fromString(policy: String): QuestionPolicy = when (policy) {
      "FIXED" -> FIXED
      "CUSTOMISABLE" -> CUSTOMISABLE
      else -> throw IllegalArgumentException("Invalid question policy: $policy")
    }
  }
}

data class QuestionTemplateDto(
  @field:Schema(description = "Question ID", required = true, exclusiveMinimumValue = 0)
  val id: Long,

  internal val policy: QuestionPolicy,

  @field:Schema(description = "Question template", required = true)
  @field:NotBlank
  val template: String,

  @field:Schema(description = "Response format", required = true)
  val responseFormat: QuestionResponseFormat,

  @field:Schema(description = "Response spec", required = true)
  val responseSpec: Map<String, Any>,

  @field:Schema(description = "Placeholder examples to be presented in a table", required = false)
  val example: String?,

  @field:Schema(description = "Question examples", required = false)
  val questionExamples: List<String>?,
)

/**
 * Safely extract placeholder names (not including the "{{" or "}}" delimiters) from the response spec.
 */
fun questionTemplatePlaceholders(responseSpec: Map<String, Any>): List<String> {
  val placeholders = responseSpec["placeholders"]
  if (placeholders is List<*>) {
    return placeholders.map { it.toString() }
  }
  return emptyList()
}

fun QuestionTemplateDto.placeholders(): List<String> = questionTemplatePlaceholders(responseSpec)

/**
 * Specifies parameters for a choice item of a custom question item
 */
data class CustomQuestionItem(
  @field:Schema(description = "Question ID", required = true, exclusiveMinimumValue = 0)
  val id: Long,

  @field:Schema(description = "Params for the custom question. Depends on question's response format", required = true)
  val params: Map<String, Any>,
)

/**
 * Specifies custom questions to be added to a checkin.
 */
@ValidQuestionParams
data class AssignCustomQuestionsRequest(
  @field:Schema(description = "List of custom questions", required = true)
  val questions: List<CustomQuestionItem>,

  @field:Schema(description = "Language (en-GB or cy-GB)", required = true)
  val language: Language,

  @field:Schema(description = "Author", required = true)
  val author: ExternalUserId,
)

data class AssignCustomQuestionsResponse(
  val expectedCheckinDate: LocalDate,
  @field:Schema(description = "List ID", required = true, exclusiveMinimumValue = 0)
  val listId: Long,
)

/**
 * Describes an already _customised_ question in a question list.
 */
data class QuestionListItemDto(
  @field:Schema(description = "Question Template", required = true)
  val template: QuestionTemplateDto,

  @field:Schema(description = "Parameters", required = true)
  val params: Map<String, Any>,
)

/**
 * Question in a form ready to be displayed to the offender.
 *
 * Note: Any templates have already been evaluated, transformations done.
 */
data class OffenderQuestion(
  val question: String,
  val format: QuestionResponseFormat,
  val spec: Map<String, Any>,
)

/**
 * List of questions in a form ready to be displayed to the offender.
 */
data class OffenderQuestionList(
  val listId: Long,
  val questions: List<OffenderQuestion>,
)

data class UpcomingQuestionAssignmentInfo(
  val expectedCheckinDate: LocalDate,
  val questionList: Long?,
)

data class UpcomingQuestionAssignmentResponse(
  val upcomingAssignment: UpcomingQuestionAssignmentInfo?,
)

data class UpcomingQuestionListItems(
  val expectedCheckinDate: LocalDate,
  val items: List<QuestionListItemDto>,
)

/**
 * Returned when clients (e.g. MPOP) asks for a list of available questions
 */
data class ListQuestionTemplatesResponse(
  val templates: List<QuestionTemplateDto>,
)

/**
 * Contains upcoming question list items (templates + params)
 */
data class UpcomingQuestionItemsResponse(
  val upcoming: UpcomingQuestionListItems,
)

data class UpcomingOffenderQuestions(
  val expectedCheckinDate: LocalDate,
  val questions: List<OffenderQuestion>,
)

/**
 * Questions for a checkin. If no custom questions were assigned, returns the default list.
 */
data class OffenderCheckinQuestionsResponse(
  val questions: List<OffenderQuestion>,
)
