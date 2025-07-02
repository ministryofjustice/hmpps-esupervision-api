package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

data class PractitionerDto(
  @Schema(description = "Practitioner's unique ID (this can be NDelius ID)", required = true)
  @field:NotBlank
  val uuid: String,
  val firstName: String,
  val lastName: String,
  val email: String,
  val phoneNumber: String? = null,
  val roles: List<String> = listOf(),
)
