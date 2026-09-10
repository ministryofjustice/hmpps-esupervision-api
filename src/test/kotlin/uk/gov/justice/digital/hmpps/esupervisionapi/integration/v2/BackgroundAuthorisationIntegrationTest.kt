package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.IArnsApiClient
import java.util.concurrent.Executors

/**
 * Proves that NDelius calls made with no servlet request in scope - the scheduled jobs - still
 * reach NDelius authenticated.
 *
 * `TierAuthorisationIntegrationTest` covers the *spawned thread inside a request* case, where
 * `onBehalfOfRequest` can rebind the caller's request context. The jobs have no request to rebind:
 * `CheckinCreationJob.process()` and `CheckinReminderJob.process()` run on the scheduler's own
 * thread, and the only NDelius call they make is the `POST /cases` batch. If the authorising filter
 * needs a request in scope to attach a bearer, every batch lookup goes out unauthenticated, NDelius
 * answers 401, and the fallback turns that into an empty result - which the jobs read as "no contact
 * details for any of these CRNs" and report as a clean run that created nothing.
 *
 * Both endpoints are asserted deliberately. If only the batch call is unauthenticated the fault is
 * in the call; if the single lookup is unauthenticated here too - while it works from a request
 * thread - the fault is the missing request context, and every background caller has it.
 */
class BackgroundAuthorisationIntegrationTest : IntegrationTestBase() {

  private val crn = "X980484"
  private val tokenUrl = "/auth/oauth/token"

  @Autowired
  private lateinit var ndiliusApiClient: INdiliusApiClient

  @Autowired
  private lateinit var arnsApiClient: IArnsApiClient

  companion object {
    private val upstreams = WireMockServer(options().dynamicPort()).apply { start() }

    @JvmStatic
    @DynamicPropertySource
    fun upstreamUrls(registry: DynamicPropertyRegistry) {
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
    upstreams.stubFor(post(urlEqualTo("/cases")).willReturn(json("""[$caseBody]""")))
    upstreams.stubFor(get(urlEqualTo("/case/$crn")).willReturn(json(caseBody)))
    // Only needs to deserialise - both situations are required, their inner fields are not.
    upstreams.stubFor(get(urlEqualTo(arnsUrl)).willReturn(json("""{"riskInCommunity":{},"riskInCustody":{}}""")))
  }

  private val arnsUrl = "/risks/crn/$crn/widget"

  private val caseBody get() = """{"crn":"$crn","name":{"forename":"John","surname":"Doe"},"dateOfBirth":"1980-01-01"}"""

  private fun json(body: String) = aResponse().withHeader("Content-Type", "application/json").withBody(body)

  private fun bearersFor(url: String) = upstreams.allServeEvents
    .filter { it.request.url == url }
    .map { it.request.getHeader("Authorization") }

  /**
   * Runs [block] the way the scheduler does: on a thread that never handled a request, so there is
   * no `RequestContextHolder` binding and no `SecurityContextHolder` authentication to inherit.
   */
  private fun <T> offRequestThread(block: () -> T): T {
    val pool = Executors.newSingleThreadExecutor()
    try {
      return pool.submit(block).get()
    } finally {
      pool.shutdown()
    }
  }

  @Test
  fun `the batch lookup carries a bearer when it runs with no request in scope`() {
    offRequestThread { ndiliusApiClient.getContactDetailsForMultiple(listOf(crn)) }

    val bearers = bearersFor("/cases")
    assertEquals(1, bearers.size, "expected exactly one call to /cases")
    assertTrue(
      bearers.single()?.startsWith("Bearer ") == true,
      "POST /cases went out with no bearer (Authorization: ${bearers.single()}) - the scheduled jobs cannot authenticate",
    )
  }

  @Test
  fun `the single lookup carries a bearer when it runs with no request in scope`() {
    offRequestThread { ndiliusApiClient.getContactDetails(crn) }

    val bearers = bearersFor("/case/$crn")
    assertEquals(1, bearers.size, "expected exactly one call to /case/{crn}")
    assertTrue(
      bearers.single()?.startsWith("Bearer ") == true,
      "GET /case/{crn} went out with no bearer (Authorization: ${bearers.single()}) - the fault is the missing request context, not the batch endpoint",
    )
  }

  @Test
  fun `the ARNS lookup carries a bearer when it runs with no request in scope`() {
    // ARNS has no background caller today, so this is guarding the wiring rather than a live path:
    // the fault is in the shared authorising filter, not in any one client, so the first job that
    // reaches ARNS should not have to rediscover it.
    offRequestThread { arnsApiClient.getRiskWidget(crn) }

    val bearers = bearersFor(arnsUrl)
    assertEquals(1, bearers.size, "expected exactly one call to the ARNS risk widget")
    assertTrue(
      bearers.single()?.startsWith("Bearer ") == true,
      "GET $arnsUrl went out with no bearer (Authorization: ${bearers.single()})",
    )
  }

  @Test
  fun `a token is actually minted for a background call`() {
    val grantsBefore = grantsSoFar()

    offRequestThread { ndiliusApiClient.getContactDetailsForMultiple(listOf(crn)) }

    // A cached token is fine - this only fails if the filter never even tried to authorise, which
    // separates "no client resolved at all" from "resolved, but the token was rejected".
    assertTrue(
      grantsSoFar() > grantsBefore || bearersFor("/cases").single()?.startsWith("Bearer ") == true,
      "no token was minted and no bearer was sent: the authorising filter resolved no client at all",
    )
  }

  private fun grantsSoFar() = hmppsAuth.countRequestsMatching(postRequestedFor(urlEqualTo(tokenUrl)).build()).count
}
