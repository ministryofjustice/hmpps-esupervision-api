package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.InMemoryPractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerRepository

@Configuration
class TestPractitionersConfig {
  @Bean
  @Primary
  fun practitionerRepository(): PractitionerRepository {
    val practitioners = listOf(
      PRACTITIONER_ALICE,
      PRACTITIONER_BOB,
    )
    return InMemoryPractitionerRepository(practitioners)
  }
}
