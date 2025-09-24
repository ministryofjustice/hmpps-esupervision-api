package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.InMemoryPractitionerSiteRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerSiteRepository

@Configuration
class PractitionerSiteConfiguration(
  @Value("\${practitioner.site.assignments:}") val siteAssignments: String,
) {
  @Bean
  fun practitionerSiteRepository(): PractitionerSiteRepository = InMemoryPractitionerSiteRepository.fromConfig(siteAssignments)
}
