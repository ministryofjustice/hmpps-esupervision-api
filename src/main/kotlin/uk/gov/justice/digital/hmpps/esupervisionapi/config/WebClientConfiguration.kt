package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
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
  @Value("\${api.base.url.supervision-packages-api}") val supervisionPackagesApiBaseUri: String,
  @Value("\${hmpps-auth.url}") val hmppsAuthBaseUri: String,
  @Value("\${api.health-timeout:2s}") val healthTimeout: Duration,
  @Value("\${api.timeout:20s}") val timeout: Duration,
  @Value($$"${app.offender-eligibility.source-timeout-ms:2000}") val eligibilitySourceTimeoutMs: Long,
  @Value("\${api.connection-pool.max-idle-time:20s}") val connectionPoolMaxIdleTime: Duration,
  @Value("\${api.connection-pool.evict-in-background:10s}") val connectionPoolEvictInBackground: Duration,
) {
  /**
   * Shared connection pool for every authorised outbound WebClient, so the settings can't drift
   * per-client. (reactor-netty still keeps a separate sub-pool per remote host.)
   *
   * Without this, hmpps-kotlin's `authorisedWebClient` uses reactor-netty's default pool, which has
   * no `maxIdleTime`: keep-alive connections are held indefinitely. The upstream ingress/ALB closes
   * idle connections after its own keep-alive idle timeout (typically 60s on HMPPS), so we would later
   * borrow a socket the upstream already closed and fail with
   * `recvAddress(..) failed with error(-104): Connection reset by peer`.
   *
   * `maxIdleTime` therefore MUST stay below the upstream keep-alive idle timeout. The default of 20s
   * leaves a wide margin under 60s. `evictInBackground` (default 10s) reaps idle connections
   * periodically rather than only checking them on borrow, so even a reaped-late connection is gone
   * well before 60s (worst case maxIdleTime + interval = 30s).
   *
   * Override with API_CONNECTION_POOL_MAX_IDLE_TIME / API_CONNECTION_POOL_EVICT_IN_BACKGROUND.
   */
  @Bean(destroyMethod = "dispose")
  fun outboundConnectionProvider(): ConnectionProvider = ConnectionProvider.builder("outbound-api")
    .maxIdleTime(connectionPoolMaxIdleTime)
    .evictInBackground(connectionPoolEvictInBackground)
    .build()

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
  fun manageUsersApiWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder
    .filters {
      it.add(
        ExchangeFilterFunction.ofRequestProcessor { req ->
          log.info("Requesting URL: {}", req.url())
          Mono.just(req)
        },
      )
      it.add(BackgroundClientCredentialsFilter(MANAGE_USERS_API_REGISTRATION_ID, authorizedClientManager))
    }
    .pooledAuthorisedWebClient(
      authorizedClientManager,
      registrationId = MANAGE_USERS_API_REGISTRATION_ID,
      url = manageUsersApiBaseUri,
      timeout = timeout,
    )

  /**
   * The scheduled jobs are the only NDelius callers with no request in scope - the batch
   * `POST /cases` behind check-in creation and reminders - so [BackgroundClientCredentialsFilter]
   * is what keeps those authenticated. See its KDoc.
   */
  @Bean
  @Profile("!stubndilius")
  fun ndiliusApiWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder
    .filters {
      it.add(
        ExchangeFilterFunction.ofRequestProcessor { req ->
          log.info("Requesting Ndilius URL: {}", req.url())
          Mono.just(req)
        },
      )
      it.add(BackgroundClientCredentialsFilter(NDILIUS_API_REGISTRATION_ID, authorizedClientManager))
    }
    .pooledAuthorisedWebClient(
      authorizedClientManager,
      registrationId = NDILIUS_API_REGISTRATION_ID,
      url = ndiliusApiBaseUri,
      timeout = timeout,
    )

  @Bean
  fun ndeliusEligibilityWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder
    .filters {
      it.add(
        ExchangeFilterFunction.ofRequestProcessor { req ->
          log.info("Requesting nDelius eligibility URL: {}", req.url())
          Mono.just(req)
        },
      )
      it.add(BackgroundClientCredentialsFilter(NDILIUS_API_REGISTRATION_ID, authorizedClientManager))
    }
    .pooledAuthorisedWebClient(
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
  ): WebClient = buildTierApiWebClient(authorizedClientManager, authorizedClientService, builder, timeout)

  @Bean
  fun tierEligibilityWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    authorizedClientService: OAuth2AuthorizedClientService,
    builder: WebClient.Builder,
  ): WebClient = buildTierApiWebClient(
    authorizedClientManager,
    authorizedClientService,
    builder,
    Duration.ofMillis(eligibilitySourceTimeoutMs),
  )

  private fun buildTierApiWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    authorizedClientService: OAuth2AuthorizedClientService,
    builder: WebClient.Builder,
    timeout: Duration,
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
    .pooledAuthorisedWebClient(
      authorizedClientManager,
      registrationId = TIER_API_REGISTRATION_ID,
      url = tierApiBaseUri,
      timeout = timeout,
    )

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
    .pooledAuthorisedWebClient(
      authorizedClientManager,
      registrationId = ARNS_API_REGISTRATION_ID,
      url = arnsApiBaseUri,
      timeout = timeout,
    )

  /**
   * Eligibility checks will run from the scheduled jobs as well as requests, so this carries both
   * filters: [BackgroundClientCredentialsFilter] for the jobs, and [RefreshTokenOnUnauthorizedFilter]
   * outside it so a rejected token is re-minted rather than replayed.
   */
  @Bean
  @Profile("!stubsupervisionpackages")
  fun supervisionPackagesApiWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    authorizedClientService: OAuth2AuthorizedClientService,
    builder: WebClient.Builder,
  ): WebClient = buildSupervisionPackagesApiWebClient(authorizedClientManager, authorizedClientService, builder, timeout)

  @Bean
  @Profile("!stubsupervisionpackages")
  fun supervisionPackagesEligibilityWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    authorizedClientService: OAuth2AuthorizedClientService,
    builder: WebClient.Builder,
  ): WebClient = buildSupervisionPackagesApiWebClient(
    authorizedClientManager,
    authorizedClientService,
    builder,
    Duration.ofMillis(eligibilitySourceTimeoutMs),
  )

  private fun buildSupervisionPackagesApiWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    authorizedClientService: OAuth2AuthorizedClientService,
    builder: WebClient.Builder,
    timeout: Duration,
  ): WebClient = builder
    .filters {
      it.add(
        ExchangeFilterFunction.ofRequestProcessor { req ->
          log.info("Requesting Supervision Packages API URL: {}", req.url())
          Mono.just(req)
        },
      )
      it.add(RefreshTokenOnUnauthorizedFilter(SUPERVISION_PACKAGES_API_REGISTRATION_ID, authorizedClientService))
      it.add(BackgroundClientCredentialsFilter(SUPERVISION_PACKAGES_API_REGISTRATION_ID, authorizedClientManager))
    }
    .pooledAuthorisedWebClient(
      authorizedClientManager,
      registrationId = SUPERVISION_PACKAGES_API_REGISTRATION_ID,
      url = supervisionPackagesApiBaseUri,
      timeout = timeout,
    )

  /**
   * hmpps-kotlin's `authorisedWebClient`, but with its connector swapped for one backed by
   * [outboundConnectionProvider]. The library hard-codes `HttpClient.create()` (default pool, no
   * idle eviction) and gives no hook for a provider, so we keep its OAuth filter and base URL via
   * `mutate()` and rebuild only the connector with the same response timeout. Its proxy helpers are
   * internal; we honour the standard JVM proxy system properties instead (unused in deployments).
   */
  private fun WebClient.Builder.pooledAuthorisedWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    registrationId: String,
    url: String,
    timeout: Duration,
  ): WebClient = authorisedWebClient(authorizedClientManager, registrationId, url, timeout)
    .mutate()
    .clientConnector(ReactorClientHttpConnector(pooledHttpClient(outboundConnectionProvider(), timeout)))
    .build()

  // HMPPS Auth health ping is required if your service calls HMPPS Auth to get a token to call other services
  @Bean
  fun hmppsAuthHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(hmppsAuthBaseUri, healthTimeout)

  companion object {
    internal fun pooledHttpClient(connectionProvider: ConnectionProvider, responseTimeout: Duration): HttpClient = HttpClient.create(connectionProvider)
      .responseTimeout(responseTimeout)
      .proxyWithSystemProperties()

    private const val TIER_API_REGISTRATION_ID = "tier-api"
    private const val NDILIUS_API_REGISTRATION_ID = "ndilius-api"
    private const val MANAGE_USERS_API_REGISTRATION_ID = "manage-users-api"
    private const val ARNS_API_REGISTRATION_ID = "arns-api"
    private const val SUPERVISION_PACKAGES_API_REGISTRATION_ID = "supervision-packages-api"
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
