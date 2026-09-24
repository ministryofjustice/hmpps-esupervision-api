package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import uk.gov.justice.hmpps.kotlin.auth.service.GlobalPrincipalOAuth2AuthorizedClientService

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
}
