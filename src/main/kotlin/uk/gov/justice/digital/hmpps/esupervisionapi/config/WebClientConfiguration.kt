package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import uk.gov.justice.hmpps.kotlin.auth.authorisedWebClient
import uk.gov.justice.hmpps.kotlin.auth.healthWebClient
import uk.gov.justice.hmpps.kotlin.auth.service.GlobalPrincipalOAuth2AuthorizedClientService
import java.time.Duration

@Configuration
class WebClientConfiguration(
  @Value("\${api.base.url.manage-users-api}") val manageUsersApiBaseUri: String,
  @Value("\${api.base.url.ndilius-api}") val ndiliusApiBaseUri: String,
  @Value("\${api.base.url.tier-api}") val tierApiBaseUri: String,
  @Value("\${api.base.url.arns-api}") val arnsApiBaseUri: String,
  @Value("\${hmpps-auth.url}") val hmppsAuthBaseUri: String,
  @Value("\${api.health-timeout:2s}") val healthTimeout: Duration,
  @Value("\${api.timeout:20s}") val timeout: Duration,
  @Value($$"${app.offender-eligibility.source-timeout-ms:2000}") val eligibilitySourceTimeoutMs: Long,
) {
  /**
   * The token store behind [authorizedClientManager], exposed as a bean so that
   * [RefreshTokenOnUnauthorizedFilter] can evict a rejected token.
   *
   * hmpps-kotlin builds the same `GlobalPrincipalOAuth2AuthorizedClientService` privately inside
   * its own `authorizedClientManager`, leaving nothing able to reach the cache. Declaring both
   * here is behaviourally identical - its bean is `@ConditionalOnMissingBean` and backs off - it
   * just gives us a handle on the store. Safe because this service is a resource server with no
   * authorization-code login: the manager below is the only consumer.
   */
  @Bean
  fun authorizedClientService(clientRegistrationRepository: ClientRegistrationRepository): OAuth2AuthorizedClientService = GlobalPrincipalOAuth2AuthorizedClientService(clientRegistrationRepository)

  @Bean
  fun authorizedClientManager(
    clientRegistrationRepository: ClientRegistrationRepository,
    authorizedClientService: OAuth2AuthorizedClientService,
    authorizedClientProvider: OAuth2AuthorizedClientProvider,
  ): OAuth2AuthorizedClientManager = AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService)
    .apply { setAuthorizedClientProvider(authorizedClientProvider) }

  @Bean
  fun manageUsersApiWebClient(authorizedClientManager: OAuth2AuthorizedClientManager, builder: WebClient.Builder): WebClient = builder
    .filters {
      it.add(
        ExchangeFilterFunction.ofRequestProcessor { req ->
          log.info("Requesting URL: {}", req.url())
          Mono.just(req)
        },
      )
      it.add(BackgroundClientCredentialsFilter(MANAGE_USERS_API_REGISTRATION_ID, authorizedClientManager))
    }
    .authorisedWebClient(authorizedClientManager, registrationId = MANAGE_USERS_API_REGISTRATION_ID, url = manageUsersApiBaseUri, timeout = timeout)

  /**
   * The scheduled jobs are the only NDelius callers with no request in scope - the batch
   * `POST /cases` behind check-in creation and reminders - so [BackgroundClientCredentialsFilter]
   * is what keeps those authenticated. See its KDoc.
   */
  @Bean
  @Profile("!stubndilius")
  fun ndiliusApiWebClient(authorizedClientManager: OAuth2AuthorizedClientManager, builder: WebClient.Builder): WebClient = builder
    .filters {
      it.add(
        ExchangeFilterFunction.ofRequestProcessor { req ->
          log.info("Requesting Ndilius URL: {}", req.url())
          Mono.just(req)
        },
      )
      it.add(BackgroundClientCredentialsFilter(NDILIUS_API_REGISTRATION_ID, authorizedClientManager))
    }
    .authorisedWebClient(authorizedClientManager, registrationId = NDILIUS_API_REGISTRATION_ID, url = ndiliusApiBaseUri, timeout = timeout)

  @Bean
  fun ndeliusEligibilityWebClient(authorizedClientManager: OAuth2AuthorizedClientManager, builder: WebClient.Builder): WebClient = builder
    .filters { /* same NDelius filters */ }
    .authorisedWebClient(
      authorizedClientManager,
      registrationId = NDILIUS_API_REGISTRATION_ID,
      url = ndiliusApiBaseUri,
      timeout = Duration.ofMillis(eligibilitySourceTimeoutMs),
    )

  /**
   * Tier is the one upstream called off the request thread (see `OffenderService.getHeaderDetails`)
   * and the one that has been 401ing on dev. [RefreshTokenOnUnauthorizedFilter] is added before the
   * authorising filter so a rejected token is re-minted rather than degrading the case header.
   */
  @Bean
  fun tierApiWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    authorizedClientService: OAuth2AuthorizedClientService,
    builder: WebClient.Builder,
  ): WebClient = builder
    .filters {
      it.add(
        ExchangeFilterFunction.ofRequestProcessor { req ->
          log.info("Requesting Tier API URL: {}", req.url())
          Mono.just(req)
        },
      )
      it.add(RefreshTokenOnUnauthorizedFilter(TIER_API_REGISTRATION_ID, authorizedClientService))
      // Inside the refresh filter, so its retry re-mints rather than replaying the evicted token.
      it.add(BackgroundClientCredentialsFilter(TIER_API_REGISTRATION_ID, authorizedClientManager))
    }
    .authorisedWebClient(authorizedClientManager, registrationId = TIER_API_REGISTRATION_ID, url = tierApiBaseUri, timeout = timeout)

  @Bean
  fun arnsApiWebClient(authorizedClientManager: OAuth2AuthorizedClientManager, builder: WebClient.Builder): WebClient = builder
    .filters {
      it.add(
        ExchangeFilterFunction.ofRequestProcessor { req ->
          log.info("Requesting Arns API URL: {}", req.url())
          Mono.just(req)
        },
      )
      it.add(BackgroundClientCredentialsFilter(ARNS_API_REGISTRATION_ID, authorizedClientManager))
    }
    .authorisedWebClient(authorizedClientManager, registrationId = ARNS_API_REGISTRATION_ID, url = arnsApiBaseUri, timeout = timeout)

  // HMPPS Auth health ping is required if your service calls HMPPS Auth to get a token to call other services
  @Bean
  fun hmppsAuthHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(hmppsAuthBaseUri, healthTimeout)

  companion object {
    private const val TIER_API_REGISTRATION_ID = "tier-api"
    private const val NDILIUS_API_REGISTRATION_ID = "ndilius-api"
    private const val MANAGE_USERS_API_REGISTRATION_ID = "manage-users-api"
    private const val ARNS_API_REGISTRATION_ID = "arns-api"
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
