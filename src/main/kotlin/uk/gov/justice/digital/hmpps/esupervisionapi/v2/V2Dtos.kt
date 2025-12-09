package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.serialization.LocalDateDeserializer
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ========================================
// Ndilius API DTOs (matching OpenAPI spec)
// ========================================

/** Contact details from Ndilius API */
data class ContactDetails(
  @Schema(description = "Case Reference Number", required = true, example = "X123456")
  val crn: String,
  @Schema(description = "Person's name", required = true) val name: Name,
  @Schema(
    description = "Mobile phone number (optional)",
    required = false,
    example = "07700900123",
  )
  val mobile: String? = null,
  @Schema(
    description = "Email address (optional)",
    required = false,
    example = "john.smith@example.com",
  )
  val email: String? = null,
  @Schema(description = "Practitioner details (optional)", required = false)
  val practitioner: PractitionerDetails? = null,
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
  @Schema(description = "Verification result: MATCH or NO_MATCH", required = true)
  val result: AutomatedIdVerificationResult,
)

// ========================================
// V2 Offender DTOs
// ========================================

/** V2 Offender DTO (no PII, only CRN) */
data class OffenderV2Dto(
  @Schema(description = "Unique identifier", required = true) val uuid: UUID,
  @Schema(description = "Case Reference Number", required = true, example = "X123456")
  val crn: String,
  @Schema(description = "Practitioner ID", required = true)
  val practitionerId: ExternalUserId,
  @Schema(description = "Offender status", required = true) val status: OffenderStatus,
  @Schema(description = "Date of first checkin", required = true)
  @JsonDeserialize(using = LocalDateDeserializer::class)
  val firstCheckin: LocalDate,
  @Schema(description = "Interval between checkins", required = true)
  val checkinInterval: CheckinInterval,
  @Schema(description = "Created timestamp", required = true) val createdAt: Instant,
  @Schema(description = "Created by practitioner ID", required = true) val createdBy: String,
  @Schema(description = "Last updated timestamp", required = true) val updatedAt: Instant,
  @Schema(description = "Personal details from Ndilius (optional)", required = false)
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
  @Schema(description = "Setup start timestamp (optional)", required = false)
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

/** V2 Checkin DTO */
data class CheckinV2Dto(
  @Schema(description = "Unique identifier", required = true) val uuid: UUID,
  @Schema(description = "Case Reference Number", required = true) val crn: String,
  @Schema(description = "Checkin status", required = true) val status: CheckinV2Status,
  @Schema(description = "Due date", required = true)
  @JsonDeserialize(using = LocalDateDeserializer::class)
  val dueDate: LocalDate,
  @Schema(description = "Created timestamp", required = true) val createdAt: Instant,
  @Schema(description = "Created by", required = true) val createdBy: String,
  @Schema(description = "Submitted timestamp", required = false)
  val submittedAt: Instant? = null,
  @Schema(description = "Review started timestamp", required = false)
  val reviewStartedAt: Instant? = null,
  @Schema(description = "Review started by", required = false)
  val reviewStartedBy: String? = null,
  @Schema(description = "Reviewed timestamp", required = false)
  val reviewedAt: Instant? = null,
  @Schema(description = "Reviewed by", required = false) val reviewedBy: String? = null,
  @Schema(description = "Checkin started timestamp", required = false)
  val checkinStartedAt: Instant? = null,
  @Schema(description = "Auto ID check result", required = false)
  val autoIdCheck: AutomatedIdVerificationResult? = null,
  @Schema(description = "Manual ID check result", required = false)
  val manualIdCheck: ManualIdVerificationResult? = null,
  @Schema(description = "Survey responses (JSONB)", required = false)
  val surveyResponse: Map<String, Any>? = null,
  @Schema(description = "Personal details from Ndilius (optional)", required = false)
  val personalDetails: ContactDetails? = null,
  @Schema(description = "Presigned S3 URL for video playback", required = false)
  val videoUrl: URL? = null,
)

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
  @Schema(description = "Review notes", required = false) val notes: String? = null,
)

/** Review started request */
data class ReviewStartedRequest(
  @Schema(description = "Practitioner ID who started review", required = true)
  @field:NotBlank
  val practitionerId: ExternalUserId,
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
)
