package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerUuid
import java.time.Instant
import java.util.UUID

data class OffenderEventLogDto(
  val uuid: UUID,
  val logEntryType: LogEntryType,
  val comment: String,
  val practitioner: PractitionerUuid,
  val offender: UUID,
  val createdAt: Instant,
)

data class DeactivateOffenderCheckinRequest(
  @Schema()
  @field:NotBlank
  val requestedBy: PractitionerUuid,

  @Schema()
  @field:NotBlank
  val reason: String,
)
