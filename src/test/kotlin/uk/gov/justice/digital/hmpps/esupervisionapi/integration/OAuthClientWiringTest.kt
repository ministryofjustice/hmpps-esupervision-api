package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import reactor.netty.resources.ConnectionProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.config.WebClientConfiguration
import uk.gov.justice.hmpps.kotlin.auth.service.GlobalPrincipalOAuth2AuthorizedClientService
import java.time.Duration

/**
 * `WebClientConfiguration` declares the authorized-client store and manager that hmpps-kotlin
 * would otherwise build privately, so that `RefreshTokenOnUnauthorizedFilter` has something to
 * evict from. Both of the library's beans are `@ConditionalOnMissingBean`, so a future bump could
 * quietly change the wiring under us - or, if ours were ever dropped, leave two managers and an
 * ambiguous injection. Pin the shape here.
 */
class OAuthClientWiringTest : IntegrationTestBase() {

  @Autowired
  private lateinit var authorizedClientService: OAuth2AuthorizedClientService

  @Autowired
  private lateinit var authorizedClientManager: OAuth2AuthorizedClientManager

  @Test
  fun `the token store is the shared global-principal cache the refresh filter evicts from`() {
    assertInstanceOf(GlobalPrincipalOAuth2AuthorizedClientService::class.java, authorizedClientService)
  }

  @Test
  fun `the manager is backed by that store rather than by a request-scoped repository`() {
    assertInstanceOf(AuthorizedClientServiceOAuth2AuthorizedClientManager::class.java, authorizedClientManager)
  }

  @Autowired
  private lateinit var outboundConnectionProvider: ConnectionProvider

  @Autowired
  private lateinit var webClientConfiguration: WebClientConfiguration

  @Test
  fun `outbound clients share a pool that evicts idle connections below the upstream keep-alive`() {
    assertEquals("outbound-api", outboundConnectionProvider.name())
    assertEquals(Duration.ofSeconds(20), webClientConfiguration.connectionPoolMaxIdleTime)
    assertEquals(Duration.ofSeconds(10), webClientConfiguration.connectionPoolEvictInBackground)
  }

  @Test
  fun `clients get the singleton pool rather than building a new, unmanaged one each`() {
    // The WebClient beans obtain the pool by calling the @Bean method, which relies on the
    // configuration class being CGLIB-proxied.
    assertSame(outboundConnectionProvider, webClientConfiguration.outboundConnectionProvider())
  }
}
