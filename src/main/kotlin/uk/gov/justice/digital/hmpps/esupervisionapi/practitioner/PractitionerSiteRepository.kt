package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

data class PractitionerSite(
  val name: String,
)

interface PractitionerSiteRepository {
  fun findLocation(practitionerId: ExternalUserId): PractitionerSite?
}
