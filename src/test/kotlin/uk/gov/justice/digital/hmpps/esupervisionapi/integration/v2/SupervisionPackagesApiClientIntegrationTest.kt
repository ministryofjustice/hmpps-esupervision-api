package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.ISupervisionPackagesApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.SupervisionPackageDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.SupervisionPackagesFetchException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Exercises the real client bean - WebClient, authorising filters and resilience4j aspects - against
 * a stubbed Supervision Packages API.
 *
 * Calls run off a request thread, as the scheduled jobs will, so the bearer comes from
 * `BackgroundClientCredentialsFilter` rather than a servlet request in scope.
 */
class SupervisionPackagesApiClientIntegrationTest : IntegrationTestBase() {

  private val crn = "X980484"
  private val contextUrl = "/frontend-context/$crn"
  private val tokenUrl = "/auth/oauth/token"

  @Autowired
  private lateinit var client: ISupervisionPackagesApiClient

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
  }

  private fun json(body: String) = aResponse().withHeader("Content-Type", "application/json").withBody(body)

  /**
   * The full `FrontendComponentResponse` shape, including the fields the client does not map, so the
   * test also proves those are ignored rather than failing deserialisation.
   */
  private fun frontendContext(currentPhase: String?, recallStatus: String?) = json(
    """
    {
      "currentPhase": $currentPhase,
      "earlyEngagement": {"startDate": "2026-06-01T00:00:00+01:00", "endDate": "2026-08-24T00:00:00+01:00", "weeks": 12, "completed": 4},
      "currentYear": {"startDate": "2026-06-01", "endDate": "2027-05-31", "maximum": 24, "completed": 4},
      "nextAppointment": {"id": 123, "date": "2026-09-20", "startTime": "10:00:00", "type": {"code": "COAP", "description": "Office appointment"}, "description": null},
      "createdAt": "2026-06-01T09:00:00+01:00",
      "updatedAt": "2026-06-02T09:00:00+01:00",
      "context": {
        "name": {"forename": "John", "middleNames": null, "surname": "Doe"},
        "gender": "Male",
        "sentences": [
          {
            "eventNumber": "1",
            "startDate": "2026-06-01",
            "endDate": "2028-06-01",
            "supervisionPackage": {"code": "SPC", "description": "C"},
            "type": {"code": "SC", "description": "CJA - Std Determinate Custody", "isCustodial": true},
            "custody": {"status": {"code": "B", "description": "Released - On Licence"}, "location": null, "finalThirdDate": "2027-10-01", "releases": [{"releaseDate": "2026-06-01", "recallDate": null}]},
            "inBreach": false
          }
        ],
        "integratedOffenderManagementRedRated": false,
        "offenderPersonalDisorderPathway": false,
        "intensiveSupervisionCourt": false,
        "nationalSecurityDivision": false,
        "contactSuspendedDate": null,
        "finalThirdEligibility": {"eligible": true, "since": null},
        "liferCategory": null,
        "recallStatus": $recallStatus
      }
    }
    """.trimIndent(),
  )

  private val earlyEngagementPhase = """
    {"supervisionPackage": {"code": "SPC", "description": "C"}, "phase": {"code": "INIT", "description": "Early engagement"}, "eventNumber": "1", "startDate": "2026-06-01T00:00:00+01:00", "endDate": "2026-08-24T00:00:00+01:00"}
  """.trimIndent()

  private val recallInProgress = """{"code": "REC01", "description": "Recall initiated"}"""

  private fun callsTo(url: String) = upstream.allServeEvents.filter { it.request.url == url }

  /** Runs [block] the way the scheduler does: on a thread that never handled a request. */
  private fun <T> offRequestThread(block: () -> T): T {
    val pool = Executors.newSingleThreadExecutor()
    try {
      return pool.submit(block).get()
    } catch (e: ExecutionException) {
      throw e.cause!!
    } finally {
      pool.shutdown()
    }
  }

  @Test
  fun `returns package, phase and recall status, sent with a bearer`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(frontendContext(earlyEngagementPhase, recallInProgress)))

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }

    assertEquals(
      SupervisionPackageDetails(
        supervisionPackage = CodedDescription("SPC", "C"),
        phase = CodedDescription("INIT", "Early engagement"),
        recallStatus = CodedDescription("REC01", "Recall initiated"),
      ),
      details,
    )
    val bearer = callsTo(contextUrl).single().request.getHeader("Authorization")
    assertTrue(bearer?.startsWith("Bearer ") == true, "expected a bearer, got $bearer")
  }

  @Test
  fun `a known CRN with no active package has no package or phase, but keeps its recall status`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(frontendContext(currentPhase = "null", recallStatus = recallInProgress)))

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }

    assertEquals(SupervisionPackageDetails(null, null, CodedDescription("REC01", "Recall initiated")), details)
  }

  @Test
  fun `no recall status is null`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(frontendContext(earlyEngagementPhase, recallStatus = "null")))

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }

    assertNull(details?.recallStatus)
  }

  @Test
  fun `an unknown CRN is null, and is not retried`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(aResponse().withStatus(HttpStatus.NOT_FOUND.value())))

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }

    assertNull(details)
    assertEquals(1, callsTo(contextUrl).size)
  }

  @Test
  fun `an upstream error is retried, then fails closed rather than reading as no data`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(aResponse().withStatus(HttpStatus.SERVICE_UNAVAILABLE.value())))

    val thrown = assertThrows<SupervisionPackagesFetchException> { offRequestThread { client.getSupervisionPackageDetails(crn) } }

    assertEquals(crn, thrown.crn)
    // Proves the retry wraps the circuit breaker's call: had the fallback been on the circuit
    // breaker, the first 503 would have become the exception and there would be one call, not three.
    assertEquals(3, callsTo(contextUrl).size)
  }

  @Test
  fun `a missing role fails closed without retrying`() {
    // What every call looks like before PROBATION_API__SUPERVISION_PACKAGE__READ is granted.
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(aResponse().withStatus(HttpStatus.FORBIDDEN.value())))

    assertThrows<SupervisionPackagesFetchException> { offRequestThread { client.getSupervisionPackageDetails(crn) } }

    assertEquals(1, callsTo(contextUrl).size)
  }

  @Test
  fun `a rejected token is evicted, re-minted, and the retry carries the new one`() {
    // Warm this registration's cached token first, so the grant under test is the re-mint.
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(frontendContext(earlyEngagementPhase, recallStatus = "null")))
    offRequestThread { client.getSupervisionPackageDetails(crn) }
    upstream.resetRequests()

    hmppsAuth.stubFor(post(urlEqualTo(tokenUrl)).willReturn(json("""{"token_type":"bearer","access_token":"FRESH","expires_in":3600}""")))
    upstream.stubFor(
      get(urlEqualTo(contextUrl)).withHeader("Authorization", equalTo("Bearer ABCDE"))
        .willReturn(aResponse().withStatus(HttpStatus.UNAUTHORIZED.value())),
    )
    upstream.stubFor(
      get(urlEqualTo(contextUrl)).withHeader("Authorization", equalTo("Bearer FRESH"))
        .willReturn(frontendContext(earlyEngagementPhase, recallStatus = "null")),
    )

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }

    assertEquals(CodedDescription("INIT", "Early engagement"), details?.phase)
    assertEquals(listOf("Bearer ABCDE", "Bearer FRESH"), callsTo(contextUrl).reversed().map { it.request.getHeader("Authorization") })
  }
}
