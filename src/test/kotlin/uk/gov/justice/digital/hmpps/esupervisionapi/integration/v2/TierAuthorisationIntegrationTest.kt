package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth

/**
 * Proves the Tier lookup reaches Tier authenticated, through the real endpoint.
 *
 * This needs the full application context, not a hand-wired `WebClient`.
 * `ServletOAuth2AuthorizedClientExchangeFilterFunction` does not read `RequestContextHolder`
 * itself - it reads a Reactor context key that Spring Security's `SecurityReactorContextSubscriber`
 * (installed by `@EnableWebSecurity` as a global Reactor hook) fills in *at subscribe time, on the
 * subscribing thread*. Outside a Spring Security context that hook does not exist, so no bearer is
 * ever attached and a standalone test would pass or fail for the wrong reason.
 *
 * `OffenderService` subscribes on a spawned virtual thread - hence `onBehalfOfRequest`, and hence
 * this test.
 */
class TierAuthorisationIntegrationTest : IntegrationTestBase() {

  private val crn = "X980484"
  private val tierUrl = "/v2/crn/$crn/tier"
  private val tokenUrl = "/auth/oauth/token"

  companion object {
    private val upstreams = WireMockServer(options().dynamicPort()).apply { start() }

    @JvmStatic
    @DynamicPropertySource
    fun upstreamUrls(registry: DynamicPropertyRegistry) {
      registry.add("api.base.url.tier-api") { upstreams.baseUrl() }
      registry.add("api.base.url.ndilius-api") { upstreams.baseUrl() }
      registry.add("api.base.url.arns-api") { upstreams.baseUrl() }
    }

    @JvmStatic
    @AfterAll
    fun stopUpstreams() = upstreams.stop()
  }

  @BeforeEach
  fun resetUpstreams() {
    upstreams.resetAll()
    hmppsAuth.stubGrantToken()
    upstreams.stubFor(
      get(urlEqualTo("/case/$crn")).willReturn(
        json("""{"crn":"$crn","name":{"forename":"John","surname":"Doe"},"dateOfBirth":"1980-01-01"}"""),
      ),
    )
  }

  private fun json(body: String) = aResponse().withHeader("Content-Type", "application/json").withBody(body)

  private fun tierDetails() = json("""{"tierScore":"D2","calculationId":"11111111-2222-3333-4444-555555555555","calculationDate":"2026-01-01","changeReason":null}""")

  private fun unauthorized() = aResponse()
    .withStatus(HttpStatus.UNAUTHORIZED.value())
    .withHeader("WWW-Authenticate", """Bearer error="invalid_token", error_description="Jwt expired"""")

  private fun grantsSoFar() = hmppsAuth.countRequestsMatching(postRequestedFor(urlEqualTo(tokenUrl)).build()).count

  private fun tierBearers() = upstreams.allServeEvents
    .filter { it.request.url == tierUrl }
    .reversed()
    .map { it.request.getHeader("Authorization") }

  private fun fetchHeader() = webTestClient.get()
    .uri("/v2/offenders/header/$crn")
    .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
    .exchange()

  @Test
  fun `the tier lookup carries a bearer even though it runs on a spawned thread`() {
    upstreams.stubFor(get(urlEqualTo(tierUrl)).willReturn(tierDetails()))

    fetchHeader().expectStatus().isOk.expectBody().jsonPath("$.tierScore").isEqualTo("D2")

    // The regression this guards: with the request context left behind on the calling thread the
    // authorising filter quietly sends no Authorization header at all, and Tier answers 401. Which
    // token it is does not matter - the app-wide cache outlives any one test - only that there is one.
    assertEquals(1, tierBearers().size)
    assertTrue(tierBearers().single()?.startsWith("Bearer ") == true, "expected a bearer, got ${tierBearers().single()}")
  }

  @Test
  fun `a rejected token is evicted, re-minted, and the retry carries the new one`() {
    // Warm every registration's cached token first, so the only grant left to observe is Tier's.
    upstreams.stubFor(get(urlEqualTo(tierUrl)).willReturn(tierDetails()))
    fetchHeader().expectStatus().isOk
    upstreams.resetRequests()

    // From here HMPPS Auth issues a different token, and Tier only accepts that one.
    hmppsAuth.stubFor(post(urlEqualTo(tokenUrl)).willReturn(json("""{"token_type":"bearer","access_token":"FRESH","expires_in":3600}""")))
    upstreams.stubFor(get(urlEqualTo(tierUrl)).withHeader("Authorization", equalTo("Bearer ABCDE")).willReturn(unauthorized()))
    upstreams.stubFor(get(urlEqualTo(tierUrl)).withHeader("Authorization", equalTo("Bearer FRESH")).willReturn(tierDetails()))
    val grantsBefore = grantsSoFar()

    fetchHeader().expectStatus().isOk.expectBody().jsonPath("$.tierScore").isEqualTo("D2")

    // The stale token really was dropped rather than replayed: exactly one new grant, and the
    // second attempt presented what it bought.
    assertEquals(listOf("Bearer ABCDE", "Bearer FRESH"), tierBearers())
    assertEquals(grantsBefore + 1, grantsSoFar())
  }

  @Test
  fun `a persistent 401 is retried once and then degrades the header rather than failing it`() {
    upstreams.stubFor(get(urlEqualTo(tierUrl)).willReturn(unauthorized()))

    fetchHeader().expectStatus().isOk
      .expectBody()
      .jsonPath("$.tierScore").doesNotExist()
      .jsonPath("$.errors[?(@.field == 'tierScore')].code").isEqualTo("REQUEST_REJECTED")

    assertEquals(2, tierBearers().size)
  }
}
