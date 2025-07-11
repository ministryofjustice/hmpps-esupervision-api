package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocalDateDeserializer
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class OffenderDto(
  val uuid: UUID,
  val firstName: String,
  val lastName: String,
  @JsonDeserialize(using = LocalDateDeserializer::class) val dateOfBirth: LocalDate?,
  val status: OffenderStatus = OffenderStatus.INITIAL,
  val createdAt: Instant,
  // val updatedAt: Instant,
  val email: String? = null,
  val phoneNumber: String? = null,
  // not every context requires the photo URL, so we only include when needed
  @Schema(description = "A presigned S3 URL")
  val photoUrl: URL?,
  @JsonDeserialize(using = LocalDateDeserializer::class) val nextCheckinDate: LocalDate,
  val checkinInterval: CheckinInterval,
)

data class OffenderSetupDto(
  val uuid: UUID,
  @Schema(description = "Practitioner's unique ID (this can be NDelius ID)", required = true)
  @field:NotBlank
  val practitioner: String,
  @Schema(description = "Offender's unique ID", required = true)
  val offender: UUID,
  val createdAt: Instant,
)
