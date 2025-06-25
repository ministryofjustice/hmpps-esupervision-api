package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

data class PractitionerDto(
  val uuid: String,
  val firstName: String,
  val lastName: String,
  val email: String,
  val phoneNumber: String? = null,
  val roles: List<String> = listOf(),
)
