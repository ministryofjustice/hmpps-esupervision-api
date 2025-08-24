package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ManageUsersApiPractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.NewPractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.RestManageUsersApiClient

@Configuration
@Profile("!local & !test")
class PractitionerConfiguration {
  @Bean
  fun practitionerRepository(manageUsersApiWebClient: WebClient): NewPractitionerRepository {
    val client = RestManageUsersApiClient(manageUsersApiWebClient)
    return ManageUsersApiPractitionerRepository(client)
  }
}
