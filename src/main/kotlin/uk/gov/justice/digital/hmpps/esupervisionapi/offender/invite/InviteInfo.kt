package uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocalDateDeserializer
import java.time.LocalDate
import java.util.UUID

data class InviteInfo(
  val invitees: List<OffenderInfo> = emptyList(),
)

/**
 * Minimal information required to send invite to an offender
 */
data class OffenderInfo(
  val setupUuid: UUID,
  val practitionerId: String,
  val firstName: String,
  val lastName: String,
  @JsonDeserialize(using = LocalDateDeserializer::class) val dateOfBirth: LocalDate,
  val email: String? = null,
  val phoneNumber: String? = null,
)
