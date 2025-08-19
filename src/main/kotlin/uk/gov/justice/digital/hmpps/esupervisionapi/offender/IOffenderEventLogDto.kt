package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerUuid
import java.time.Instant
import java.util.UUID

interface CheckinReference {
  val checkin: UUID
}

/**
 * For events not related to a particular checkin
 */
interface IOffenderEventLogDto {
  val uuid: UUID
  val logEntryType: LogEntryType
  val comment: String
  val practitioner: PractitionerUuid
  val offender: UUID
  val createdAt: Instant
}

interface IOffenderCheckinEventLogDto :
  IOffenderEventLogDto,
  CheckinReference

data class OffenderEventLogDto(
  override val uuid: UUID,
  override val logEntryType: LogEntryType,
  override val comment: String,
  override val practitioner: PractitionerUuid,
  override val offender: UUID,
  override val createdAt: Instant,
) : IOffenderEventLogDto

data class OffenderCheckinEventLogDto(
  override val uuid: UUID,
  override val logEntryType: LogEntryType,
  override val comment: String,
  override val practitioner: PractitionerUuid,
  override val offender: UUID,
  override val createdAt: Instant,
  override val checkin: UUID,
) : IOffenderEventLogDto,
  CheckinReference

data class DeactivateOffenderCheckinRequest(
  @Schema()
  @field:NotBlank
  val requestedBy: PractitionerUuid,

  @Schema()
  @field:NotBlank
  val reason: String,
)
