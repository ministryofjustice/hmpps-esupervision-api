package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.InMemoryPractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerRepository

@Configuration
@Profile("local")
@ConfigurationProperties(prefix = "local")
class LocalPractitionerConfiguration {
  lateinit var practitioners: List<Practitioner>

  @Bean
  @Primary
  fun practitionerRepository(): PractitionerRepository = InMemoryPractitionerRepository(practitioners)
}
