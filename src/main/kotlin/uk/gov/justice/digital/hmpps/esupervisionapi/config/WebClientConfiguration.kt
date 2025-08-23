package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.InMemoryPractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.NewPractitioner
import uk.gov.justice.hmpps.kotlin.auth.authorisedWebClient
import uk.gov.justice.hmpps.kotlin.auth.healthWebClient
import java.time.Duration

@Configuration
class WebClientConfiguration(
  @Value("\${api.base.url.manage-users-api}") val manageUsersApiBaseUri: String,
  @Value("\${hmpps-auth.url}") val hmppsAuthBaseUri: String,
  @Value("\${api.health-timeout:2s}") val healthTimeout: Duration,
  @Value("\${api.timeout:20s}") val timeout: Duration,
  @Value("\${practitioner.users:}") val practitionerUsersConfig: String,
) {
  @Bean
  fun manageUsersApiWebClient(authorizedClientManager: OAuth2AuthorizedClientManager, builder: WebClient.Builder): WebClient {
    return builder
      .filters {
        it.add(ExchangeFilterFunction.ofRequestProcessor { req ->
          log.info("Requesting URL: {}", req.url());
          Mono.just(req)
        })
      }
      .authorisedWebClient(authorizedClientManager, registrationId = "manage-users-api", url = manageUsersApiBaseUri, timeout = timeout)
  }
  // HMPPS Auth health ping is required if your service calls HMPPS Auth to get a token to call other services
  @Bean
  fun hmppsAuthHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(hmppsAuthBaseUri, healthTimeout)

  // TODO: only load this if test practitioner are configured!
  @Bean
  @Primary
  fun practitionerUsersRepository() : InMemoryPractitionerRepository {
    val practitioners = mutableListOf<NewPractitioner>()

    practitionerUsersConfig.split("|").forEach { record ->
      val fields = record.split(",")
      if (fields.size == 2) {
        val username = fields[0].trim()
        val email = fields[1].trim()
        practitioners.add(NewPractitioner(username, email))
      } else {
        log.warn("Invalid practitioner record: {}", record)
      }
    }

    return InMemoryPractitionerRepository(practitioners)
  }

  companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
