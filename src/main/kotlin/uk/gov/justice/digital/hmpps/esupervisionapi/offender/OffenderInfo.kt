package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocalDateDeserializer
import java.time.LocalDate
import java.util.UUID

/**
 * Minimal information required to start offender onboarding.
 */
data class OffenderInfo(
  val setupUuid: UUID,

  @field:NotBlank
  val practitionerId: String,

  @field:Size(min = 2)
  val firstName: String,

  @field:Size(min = 2)
  val lastName: String,

  @JsonDeserialize(using = LocalDateDeserializer::class) val dateOfBirth: LocalDate,
  @field:Email
  val email: String? = null,

  val phoneNumber: String? = null,
)
