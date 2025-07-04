package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import java.net.URL
import java.time.Instant
import java.util.UUID

enum class CheckinStatus {
  CREATED,
  SUBMITTED,
  REVIEWED,
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

data class OffenderCheckinDto(
  val uuid: UUID,
  val status: CheckinStatus,
  val dueDate: Instant,
  val offender: OffenderDto,
  val submittedOn: Instant?,
  val questions: String,
  val answers: String?,
  val createdBy: String,
  val createdAt: Instant,
  val reviewedBy: String?,
  /**
   * Will be set to pre-signed S3 URL
   */
  val videoUrl: URL?,
  val autoIdCheck: AutomatedIdVerificationResult?,
  val manualIdCheck: ManualIdVerificationResult?,
)

/**
 * Holds data submitted as a checkin by the offender.
 */
data class OffenderCheckinSubmission(
  val offender: UUID,
  val answers: String,
)
