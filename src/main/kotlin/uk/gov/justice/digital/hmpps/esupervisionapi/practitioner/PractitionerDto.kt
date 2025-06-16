package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import java.util.UUID

data class PractitionerDto(
  val uuid: UUID,
  val firstName: String,
  val lastName: String,
  val email: String,
  val phoneNumber: String? = null,
  val roles: List<String> = listOf(),
)
