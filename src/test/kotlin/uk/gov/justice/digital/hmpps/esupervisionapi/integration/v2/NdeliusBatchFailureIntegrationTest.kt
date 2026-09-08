package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NdiliusBatchFetchException

/**
 * A failed batch must not look like an empty one.
 *
 * `getContactDetailsForMultiple` used to fall back to an empty list on any failure. The jobs read
 * that as "NDelius holds none of these CRNs", so when every call 401'd on dev the check-in creation
 * job processed 282 offenders, created nothing, and reported `processed=0, created=0, failed=0` -
 * a clean run. Nothing alerted, because from the job's side nothing had gone wrong.
 *
 * These pin the distinction the jobs rely on: an upstream failure throws, an upstream that genuinely
 * knows none of the CRNs returns empty.
 */
class NdeliusBatchFailureIntegrationTest : IntegrationTestBase() {

  private val crns = listOf("X980484", "X980485")

  @Autowired
  private lateinit var ndiliusApiClient: INdiliusApiClient

  companion object {
    private val upstreams = WireMockServer(options().dynamicPort()).apply { start() }

    @JvmStatic
    @DynamicPropertySource
    fun upstreamUrls(registry: DynamicPropertyRegistry) {
      registry.add("api.base.url.ndilius-api") { upstreams.baseUrl() }
    }

    @JvmStatic
    @AfterAll
    fun stopUpstreams() = upstreams.stop()
  }

  @BeforeEach
  fun resetUpstreams() {
    upstreams.resetAll()
    hmppsAuth.stubGrantToken()
  }

  private fun stubCases(status: Int, body: String? = null) {
    upstreams.stubFor(
      post(urlEqualTo("/cases")).willReturn(
        aResponse().withStatus(status)
          .withHeader("Content-Type", "application/json")
          .withBody(body ?: ""),
      ),
    )
  }

  @Test
  fun `a 401 from NDelius throws rather than reporting an empty batch`() {
    stubCases(HttpStatus.UNAUTHORIZED.value())

    val thrown = assertThrows<NdiliusBatchFetchException> { ndiliusApiClient.getContactDetailsForMultiple(crns) }

    // CheckinCreationJob counts `e.crns.size` towards its failure total, so the CRNs have to survive.
    assertEquals(crns, thrown.crns)
  }

  @Test
  fun `a 5xx from NDelius throws rather than reporting an empty batch`() {
    stubCases(HttpStatus.INTERNAL_SERVER_ERROR.value())

    assertThrows<NdiliusBatchFetchException> { ndiliusApiClient.getContactDetailsForMultiple(crns) }
  }

  @Test
  fun `an empty result is still returned when NDelius genuinely knows none of the CRNs`() {
    stubCases(HttpStatus.OK.value(), "[]")

    assertEquals(emptyList<Any>(), ndiliusApiClient.getContactDetailsForMultiple(crns))
  }
}
