package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth

/**
 * ESUP-2186: with the switch-over in the past, the header reads Tier v3 through the real
 * application context - the app's own JSON codecs, not a hand-built client - and links to the v3
 * Tier UI page.
 */
class TierApiV3IntegrationTest : IntegrationTestBase() {

  private val crn = "X980485"

  companion object {
    private val upstreams = WireMockServer(options().dynamicPort()).apply { start() }

    @JvmStatic
    @DynamicPropertySource
    fun upstreamUrls(registry: DynamicPropertyRegistry) {
      registry.add("api.base.url.tier-api") { upstreams.baseUrl() }
      registry.add("api.base.url.ndilius-api") { upstreams.baseUrl() }
      registry.add("api.base.url.arns-api") { upstreams.baseUrl() }
      registry.add("api.base.url.tier-ui") { "https://tier-ui.test" }
      registry.add("app.features.esup-2186-tier-v3-from") { "2026-01-01T00:00:00Z" }
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

  private fun tierV3(score: String, provisional: Boolean = false) = json(
    """{"tierScore":"$score","calculationId":"11111111-2222-3333-4444-555555555555","calculationDate":"2026-10-01T08:15:30.123","changeReason":null,"provisional":$provisional}""",
  )

  private fun fetchHeader() = webTestClient.get()
    .uri("/v2/offenders/header/$crn")
    .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
    .exchange()

  @Test
  fun `the header carries the v3 tier and links to the v3 Tier UI page`() {
    upstreams.stubFor(get(urlEqualTo("/v3/crn/$crn/tier")).willReturn(tierV3("E")))

    fetchHeader().expectStatus().isOk
      .expectBody()
      .jsonPath("$.tierScore").isEqualTo("E")
      .jsonPath("$.tierScoreProvisional").isEqualTo(false)
      .jsonPath("$.tierDetailsLink").isEqualTo("https://tier-ui.test/v3/case/$crn")
      .jsonPath("$.errors[?(@.field == 'tierScore')]").doesNotExist()
  }

  @Test
  fun `a provisional v3 tier is flagged as such`() {
    upstreams.stubFor(get(urlEqualTo("/v3/crn/$crn/tier")).willReturn(tierV3("E", provisional = true)))

    fetchHeader().expectStatus().isOk
      .expectBody()
      .jsonPath("$.tierScore").isEqualTo("E")
      .jsonPath("$.tierScoreProvisional").isEqualTo(true)
  }

  @Test
  fun `a case v3 does not tier is reported as a missing tier, not as a score`() {
    upstreams.stubFor(get(urlEqualTo("/v3/crn/$crn/tier")).willReturn(tierV3("NOT_SUPERVISED")))

    fetchHeader().expectStatus().isOk
      .expectBody()
      .jsonPath("$.tierScore").doesNotExist()
      .jsonPath("$.tierScoreProvisional").doesNotExist()
      .jsonPath("$.errors[?(@.field == 'tierScore')].code").isEqualTo("NOT_FOUND")
  }
}
