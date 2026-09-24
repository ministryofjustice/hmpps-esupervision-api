package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth

/**
 * `GET /v2/offenders/crn/{crn}/supervision-package` through the real client, against a stubbed
 * Supervision Packages API serving responses captured from dev (see
 * [SupervisionPackagesApiClientIntegrationTest]).
 */
class SupervisionPackageStatusIntegrationTest : IntegrationTestBase() {

  private val crn = "Y051990"
  private val contextUrl = "/frontend-context/$crn"

  @Autowired
  private lateinit var circuitBreakers: CircuitBreakerRegistry

  companion object {
    private val upstream = WireMockServer(options().dynamicPort()).apply { start() }

    @JvmStatic
    @DynamicPropertySource
    fun upstreamUrls(registry: DynamicPropertyRegistry) {
      registry.add("api.base.url.supervision-packages-api") { upstream.baseUrl() }
    }

    @JvmStatic
    @AfterAll
    fun stopUpstream() = upstream.stop()
  }

  @BeforeEach
  fun resetUpstream() {
    upstream.resetAll()
    hmppsAuth.stubGrantToken()
    // The outage test's failures would otherwise open the circuit for whichever tests run after it.
    circuitBreakers.circuitBreaker("supervisionPackagesApi").reset()
  }

  private val mapper = jacksonObjectMapper()

  private fun frontendContext(capturedCrn: String, adjust: ObjectNode.() -> Unit = {}): ResponseDefinitionBuilder {
    val response = javaClass.getResourceAsStream("/supervision-packages-api-responses/frontend-context-$capturedCrn.json")!!
      .use { mapper.readTree(it) as ObjectNode }
    return aResponse().withHeader("Content-Type", "application/json").withBody(mapper.writeValueAsString(response.apply(adjust)))
  }

  private fun fetchStatus(crnInPath: String = crn, roles: List<String> = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")) = webTestClient.get()
    .uri("/v2/offenders/crn/$crnInPath/supervision-package")
    .headers(setAuthorisation(roles = roles))
    .exchange()

  private fun expectOnSupervisionPackage(expected: Boolean) = fetchStatus()
    .expectStatus().isOk
    .expectBody().jsonPath("$.onSupervisionPackage").isEqualTo(expected)

  @Test
  fun `package A is on a supervision package, and the lookup carries a bearer`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(frontendContext("Y051990")))

    expectOnSupervisionPackage(true)

    val bearer = upstream.allServeEvents.single { it.request.url == contextUrl }.request.getHeader("Authorization")
    assertTrue(bearer?.startsWith("Bearer ") == true, "expected a bearer, got $bearer")
  }

  @Test
  fun `the CRN is normalised before the lookup`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(frontendContext("Y051990")))

    fetchStatus(crnInPath = " ${crn.lowercase()} ").expectStatus().isOk
      .expectBody().jsonPath("$.onSupervisionPackage").isEqualTo(true)
  }

  @Test
  fun `a package not yet known is not on a supervision package`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(frontendContext("Y058556")))

    expectOnSupervisionPackage(false)
  }

  @Test
  fun `a package on another sentence counts, whichever sentence the current phase is on`() {
    upstream.stubFor(
      get(urlEqualTo(contextUrl)).willReturn(
        frontendContext("Y058556") {
          val sentences = get("context").get("sentences") as ArrayNode
          val second = (sentences.get(0) as ObjectNode).deepCopy().apply {
            put("eventNumber", "2")
            set("supervisionPackage", mapper.readTree("""{"code":"SPB","description":"B"}"""))
          }
          sentences.add(second)
        },
      ),
    )

    expectOnSupervisionPackage(true)
  }

  @Test
  fun `no current phase and no sentences is not on a supervision package`() {
    upstream.stubFor(
      get(urlEqualTo(contextUrl)).willReturn(
        frontendContext("Y051990") {
          remove("currentPhase")
          (get("context") as ObjectNode).set("sentences", mapper.createArrayNode())
        },
      ),
    )

    expectOnSupervisionPackage(false)
  }

  @Test
  fun `a CRN Supervision Packages does not know is 404, not false`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(aResponse().withStatus(HttpStatus.NOT_FOUND.value())))

    fetchStatus().expectStatus().isNotFound
  }

  @Test
  fun `an outage is 503, not false`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(aResponse().withStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())))

    fetchStatus().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
      .expectBody().jsonPath("$.onSupervisionPackage").doesNotExist()
  }

  @Test
  fun `the UI role is required`() {
    fetchStatus(roles = listOf("ROLE_SOMETHING_ELSE")).expectStatus().isForbidden
  }
}
