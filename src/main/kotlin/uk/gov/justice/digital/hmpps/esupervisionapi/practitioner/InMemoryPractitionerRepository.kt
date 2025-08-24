package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

class InMemoryPractitionerRepository(
  val practitioners: List<NewPractitioner>,
) : NewPractitionerRepository {
  override fun findById(id: ExternalUserId): NewPractitioner? = practitioners.find { it.externalUserId() == id }
}
