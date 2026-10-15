package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityRuleOutcome.CONTINUE
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityRuleOutcome.NOT_ELIGIBLE
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.EligibilityCheckResponse.RuleResult
import java.util.UUID

@TestPropertySource(properties = ["app.offender-eligibility.rule-set=OFFENDER_ELIGIBILITY"])
class OffenderResourceIT : IntegrationTestBase() {

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  private val crn = "X987654"
  private var offenderId: Long? = null

  @BeforeEach
  fun setUp() {
    upstreams.resetAll()
    hmppsAuth.stubGrantToken()
    offenderId = offenderRepository.saveAndFlush(
      offenderTemplate.copy(uuid = UUID.randomUUID(), crn = crn).toEntity(),
    ).id

    upstreams.stubFor(
      get(urlEqualTo("/case/$crn")).willReturn(
        json(
          """
          {
            "crn": "$crn",
            "name": {"forename": "John", "surname": "Doe"},
            "dateOfBirth": "1980-01-01",
            "practitioner": {"code": "N01A001", "name": {"forename": "Jane", "surname": "Smith"}},
            "events": [{"number": 1, "mainOffence": {"code": "001", "description": "An offence"}, "sentence": null}]
          }
          """.trimIndent(),
        ),
      ),
    )
    upstreams.stubFor(
      get(urlEqualTo("/v3/crn/$crn/tier")).willReturn(
        json(
          """
          {
            "tierScore": "B",
            "calculationId": "11111111-2222-3333-4444-555555555555",
            "calculationDate": "2026-10-01T08:15:30.123",
            "changeReason": null,
            "provisional": false
          }
          """.trimIndent(),
        ),
      ),
    )
    upstreams.stubFor(
      get(urlEqualTo("/frontend-context/$crn")).willReturn(
        json(
          """
          {
            "currentPhase": {
              "supervisionPackage": {"code": "SPB", "description": "B"},
              "phase": {"code": "INIT", "description": "Early Engagement"}
            },
            "context": {"recallStatus": null, "sentences": []}
          }
          """.trimIndent(),
        ),
      ),
    )
  }

  @AfterEach
  fun cleanUp() {
    offenderId?.let { offenderRepository.deleteById(it) }
  }

  @Test
  fun `getEligibilityByCrn with itemise false returns unknown without a report when manual answers are missing`() {
    fetchEligibility(itemise = false)
      .expectStatus().isOk
      .expectBody(EligibilityCheckResponse::class.java)
      .value { response ->
        assertThat(response).isEqualTo(
          EligibilityCheckResponse(
            outcome = EligibilityCheckResponseOutcome.UNKNOWN,
            message = "John Doe may be eligible for online check ins.",
          ),
        )
      }

    verifySourcesFetchedOnce()
  }

  @Test
  fun `getEligibilityByCrn with itemise true returns unknown with all non manual rule results`() {
    fetchEligibility(itemise = true)
      .expectStatus().isOk
      .expectBody(EligibilityCheckResponse::class.java)
      .value { response ->
        assertThat(response).isEqualTo(
          EligibilityCheckResponse(
            outcome = EligibilityCheckResponseOutcome.UNKNOWN,
            message = "John Doe may be eligible for online check ins.",
            allRules = listOf(
              RuleResult("NDELIUS", "HAS_ACTIVE_EVENT", "ACTIVE_EVENT", CONTINUE),
              RuleResult("SUP-PACK", "IS_RECALLED", "RECALLED", CONTINUE),
              RuleResult("TIER", "IS_TIER_PROVISIONAL", "PROVISIONAL", CONTINUE),
              RuleResult("SUP-PACK", "IN_FINAL_THIRD", "FINAL_THIRD", CONTINUE),
              RuleResult("NDELIUS", "IS_PRACTITIONER_ASSIGNED", "PRACTITIONER_ASSIGNED", CONTINUE),
              RuleResult("TIER", "TIER_MISSING", "TIER", CONTINUE),
              RuleResult("TIER", "IS_TIER_D_TO_G", "TIER", CONTINUE),
              RuleResult("ESUP", "IS_PILOT_USER", "PILOT_USER", CONTINUE),
              RuleResult("TIER", "IS_TIER_C", "TIER", CONTINUE),
              RuleResult(
                "SUP-PACK",
                "IN_EARLY_ENGAGEMENT",
                "EARLY_ENGAGEMENT",
                NOT_ELIGIBLE,
                "John Doe is not eligible for online check ins because they are in Tier A or B, on an accredited programme, but are in early engagement.",
              ),
            ),
          ),
        )
      }

    verifySourcesFetchedOnce()
  }

  private fun fetchEligibility(itemise: Boolean) = webTestClient.get()
    .uri("/v2/offenders/crn/$crn/eligibility?itemise=$itemise")
    .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
    .exchange()

  private fun verifySourcesFetchedOnce() {
    upstreams.verify(1, getRequestedFor(urlEqualTo("/case/$crn")))
    upstreams.verify(1, getRequestedFor(urlEqualTo("/v3/crn/$crn/tier")))
    upstreams.verify(1, getRequestedFor(urlEqualTo("/frontend-context/$crn")))
  }

  private fun json(body: String) = aResponse().withHeader("Content-Type", "application/json").withBody(body)

  companion object {
    private val upstreams = WireMockServer(options().dynamicPort()).apply { start() }

    @JvmStatic
    @DynamicPropertySource
    fun upstreamUrls(registry: DynamicPropertyRegistry) {
      registry.add("api.base.url.ndilius-api") { upstreams.baseUrl() }
      registry.add("api.base.url.tier-api") { upstreams.baseUrl() }
      registry.add("api.base.url.supervision-packages-api") { upstreams.baseUrl() }
    }

    @JvmStatic
    @AfterAll
    fun stopUpstreams() = upstreams.stop()
  }
}
