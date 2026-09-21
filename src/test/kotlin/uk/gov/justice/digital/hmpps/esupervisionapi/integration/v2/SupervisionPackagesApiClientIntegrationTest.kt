package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.CustodyDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.ISupervisionPackagesApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.SupervisionPackageDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.SupervisionPackagesFetchException
import java.time.LocalDate
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Exercises the real client bean - WebClient, authorising filters and resilience4j aspects - against
 * a stubbed Supervision Packages API.
 *
 * Responses are built from a real dev response (see [frontendContext]) rather than one written from
 * the upstream models, so the tests hold the client to what the API actually sends - notably that it
 * serialises non_null, omitting absent fields rather than sending them as null.
 *
 * Calls run off a request thread, as the scheduled jobs will, so the bearer comes from
 * `BackgroundClientCredentialsFilter` rather than a servlet request in scope.
 */
class SupervisionPackagesApiClientIntegrationTest : IntegrationTestBase() {

  private val crn = "Y051990"
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

  private val mapper = jacksonObjectMapper()

  private fun json(body: String) = aResponse().withHeader("Content-Type", "application/json").withBody(body)

  /**
   * Real `GET /frontend-context/{crn}` responses captured from dev, with names replaced. They carry
   * fields the client does not map, which must be ignored. [adjust] edits a copy to produce the other
   * cases from the same real shape.
   * - `Y051990`: package A, early engagement, community order, no recall status - so, as the API
   *   sends it, no `recallStatus` key at all.
   * - `Y050768`: package not yet known, in custody on an adult custody sentence, never released.
   * - `Y058556`: package not yet known, community order, with an open "Request for Recall" NSI - set
   *   up on dev by Probation Integration.
   */
  private fun frontendContext(capturedCrn: String = "Y051990", adjust: ObjectNode.() -> Unit = {}): ResponseDefinitionBuilder {
    val response = javaClass.getResourceAsStream("/supervision-packages-api-responses/frontend-context-$capturedCrn.json")!!
      .use { mapper.readTree(it) as ObjectNode }
    return json(mapper.writeValueAsString(response.apply(adjust)))
  }

  private fun inCustody(adjust: ObjectNode.() -> Unit = {}) = frontendContext("Y050768", adjust)

  private fun openRecallRequest(adjust: ObjectNode.() -> Unit = {}) = frontendContext("Y058556", adjust)

  /** Replaces the first sentence's custody status and, if given, its location. */
  private fun ObjectNode.withCustody(status: String, location: String? = null) {
    val custody = get("context").get("sentences").get(0).get("custody") as ObjectNode
    custody.set("status", mapper.readTree(status))
    location?.let { custody.set("location", mapper.readTree(it)) }
  }

  /** Replaces the first sentence's releases - no dev response seen so far has any. */
  private fun ObjectNode.withReleases(releases: String) {
    val custody = get("context").get("sentences").get(0).get("custody") as ObjectNode
    custody.set("releases", mapper.readTree(releases))
  }

  private fun ObjectNode.withNoCurrentPhase() {
    remove("currentPhase")
  }

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
  fun `returns package and phase from a real response, with no recall status, sent with a bearer`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(frontendContext()))

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }

    assertEquals(
      SupervisionPackageDetails(
        supervisionPackage = CodedDescription("SPA", "A"),
        phase = CodedDescription("INIT", "Early Engagement"),
        recallStatus = null,
      ),
      details,
    )
    val bearer = callsTo(contextUrl).single().request.getHeader("Authorization")
    assertTrue(bearer?.startsWith("Bearer ") == true, "expected a bearer, got $bearer")
  }

  @Test
  fun `returns the status of an open recall request`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(openRecallRequest()))

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }

    assertEquals(
      SupervisionPackageDetails(
        supervisionPackage = CodedDescription("SPNK", "Not Yet Known"),
        phase = CodedDescription("SPNK", "Not Yet Known"),
        recallStatus = CodedDescription("REC01", "Recall Initiated"),
        custody = emptyList(),
      ),
      details,
    )
  }

  @Test
  fun `a known CRN with no current phase has no package or phase, but keeps its recall status`() {
    upstream.stubFor(
      get(urlEqualTo(contextUrl)).willReturn(
        openRecallRequest { withNoCurrentPhase() },
      ),
    )

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }

    assertEquals(SupervisionPackageDetails(null, null, CodedDescription("REC01", "Recall Initiated")), details)
  }

  @Test
  fun `a community sentence has no custody details`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(frontendContext()))

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }

    assertEquals(emptyList<CustodyDetails>(), details?.custody)
  }

  @Test
  fun `a custodial sentence never released reports its custody status and no release or recall`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(inCustody()))

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }

    assertEquals(CodedDescription("SENT", "In Custody"), details?.phase)
    assertEquals(
      listOf(
        CustodyDetails(
          eventNumber = "1",
          status = CodedDescription("A", "Sentenced - In Custody"),
          location = CodedDescription("UNKNOW", "Unknown"),
          latestReleaseDate = null,
          latestRecallDate = null,
        ),
      ),
      details?.custody,
    )
    assertFalse(details!!.isRecalled)
    assertFalse(details.isUnlawfullyAtLarge)
  }

  @Test
  fun `custody status C is recalled`() {
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(inCustody { withCustody(status = """{"code": "C", "description": "Recalled"}""") }))

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }!!

    assertTrue(details.isRecalled)
    assertFalse(details.isUnlawfullyAtLarge)
  }

  @Test
  fun `location UATLRG is unlawfully at large`() {
    upstream.stubFor(
      get(urlEqualTo(contextUrl)).willReturn(
        inCustody {
          withCustody(
            status = """{"code": "C", "description": "Recalled"}""",
            location = """{"code": "UATLRG", "description": "Unlawfully at Large"}""",
          )
        },
      ),
    )

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }!!

    assertEquals(CodedDescription("UATLRG", "Unlawfully at Large"), details.custody.single().location)
    assertTrue(details.isUnlawfullyAtLarge)
    assertTrue(details.isRecalled)
  }

  @Test
  fun `a recall on any sentence counts, not only the first`() {
    // The captured in-custody sentence is left as it is; a second, recalled one is added after it.
    upstream.stubFor(
      get(urlEqualTo(contextUrl)).willReturn(
        inCustody {
          val sentences = get("context").get("sentences") as ArrayNode
          val second = sentences.get(0).deepCopy() as ObjectNode
          second.put("eventNumber", "2")
          (second.get("custody") as ObjectNode).set("status", mapper.readTree("""{"code": "C", "description": "Recalled"}"""))
          sentences.add(second)
        },
      ),
    )

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }!!

    assertEquals(listOf("A", "C"), details.custody.map { it.status.code })
    assertTrue(details.isRecalled)
  }

  @Test
  fun `a recall on the most recent release is reported as the latest recall`() {
    upstream.stubFor(
      get(urlEqualTo(contextUrl)).willReturn(
        inCustody { withReleases("""[{"releaseDate": "2026-01-12", "recallDate": "2026-03-02"}]""") },
      ),
    )

    val custody = offRequestThread { client.getSupervisionPackageDetails(crn) }!!.custody.single()

    assertEquals(LocalDate.of(2026, 1, 12), custody.latestReleaseDate)
    assertEquals(LocalDate.of(2026, 3, 2), custody.latestRecallDate)
  }

  @Test
  fun `a recall followed by a later release is not reported as the latest recall`() {
    // Deliberately out of date order, so the latest release is chosen by date rather than position.
    upstream.stubFor(
      get(urlEqualTo(contextUrl)).willReturn(
        inCustody {
          withReleases(
            """[{"releaseDate": "2026-05-01"}, {"releaseDate": "2026-01-12", "recallDate": "2026-03-02"}]""",
          )
        },
      ),
    )

    val custody = offRequestThread { client.getSupervisionPackageDetails(crn) }!!.custody.single()

    assertEquals(LocalDate.of(2026, 5, 1), custody.latestReleaseDate)
    assertNull(custody.latestRecallDate)
  }

  @Test
  fun `absent fields read as null, as the real API omits them`() {
    // Also captured from dev (names replaced): a known CRN with no supervised sentences, so neither
    // currentPhase nor recallStatus is present - not sent as null, just missing.
    upstream.stubFor(
      get(urlEqualTo(contextUrl)).willReturn(
        json(
          """{"context":{"name":{"forename":"Test","surname":"Person"},"gender":"Male","sentences":[],"integratedOffenderManagementRedRated":false,"offenderPersonalDisorderPathway":false,"intensiveSupervisionCourt":false,"nationalSecurityDivision":false,"finalThirdEligibility":{"eligible":false}}}""",
        ),
      ),
    )

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }

    assertEquals(SupervisionPackageDetails(null, null, null), details)
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
    upstream.stubFor(get(urlEqualTo(contextUrl)).willReturn(frontendContext()))
    offRequestThread { client.getSupervisionPackageDetails(crn) }
    upstream.resetRequests()

    hmppsAuth.stubFor(post(urlEqualTo(tokenUrl)).willReturn(json("""{"token_type":"bearer","access_token":"FRESH","expires_in":3600}""")))
    upstream.stubFor(
      get(urlEqualTo(contextUrl)).withHeader("Authorization", equalTo("Bearer ABCDE"))
        .willReturn(aResponse().withStatus(HttpStatus.UNAUTHORIZED.value())),
    )
    upstream.stubFor(
      get(urlEqualTo(contextUrl)).withHeader("Authorization", equalTo("Bearer FRESH"))
        .willReturn(frontendContext()),
    )

    val details = offRequestThread { client.getSupervisionPackageDetails(crn) }

    assertEquals(CodedDescription("INIT", "Early Engagement"), details?.phase)
    assertEquals(listOf("Bearer ABCDE", "Bearer FRESH"), callsTo(contextUrl).reversed().map { it.request.getHeader("Authorization") })
  }
}
