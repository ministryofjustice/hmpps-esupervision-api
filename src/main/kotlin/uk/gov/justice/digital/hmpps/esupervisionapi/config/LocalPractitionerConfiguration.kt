package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.InMemoryPractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.NewPractitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.NewPractitionerRepository

@Configuration
@Profile("local")
@ConfigurationProperties(prefix = "local")
class LocalPractitionerConfiguration {
  lateinit var practitioners: List<NewPractitioner>

  @Bean
  @Primary
  fun practitionerRepository(): NewPractitionerRepository = InMemoryPractitionerRepository(practitioners)
}
