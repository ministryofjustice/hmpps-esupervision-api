package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

class InMemoryPractitionerRepository(
  val practitioners: List<Practitioner>,
) : PractitionerRepository {
  override fun findById(id: ExternalUserId): Practitioner? = practitioners.find { it.externalUserId() == id }
}
