package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

data class PractitionerSite(
  val practitioner: ExternalUserId,
  val name: String,
)

interface PractitionerSiteRepository {
  fun findLocation(practitionerId: ExternalUserId): PractitionerSite?
  fun findAll(pageable: Pageable): Page<PractitionerSite>
}
