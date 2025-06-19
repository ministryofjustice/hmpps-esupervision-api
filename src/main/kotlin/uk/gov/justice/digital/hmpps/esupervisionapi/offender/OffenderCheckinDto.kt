package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import java.net.URL
import java.time.Instant
import java.util.UUID

enum class CheckinStatus {
  SUBMITTED,
  REVIEWED,
}

data class OffenderCheckinDto(
  val uuid: UUID,
  val status: CheckinStatus,
  val offender: OffenderDto,
  val submittedOn: Instant,
  val questions: String,
  val answers: String?,
  val reviewedBy: UUID?,
  /**
   * Will be set to pre-signed S3 URL
   */
  val videoUrl: URL?,
)

data class OffenderCheckinInput(
  val checkinUuid: UUID,
  val offenderUuid: UUID,
  val answers: String,
)
