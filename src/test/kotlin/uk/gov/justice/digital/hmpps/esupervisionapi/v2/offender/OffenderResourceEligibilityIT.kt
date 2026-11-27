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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityRuleOutcome.CONTINUE
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityRuleOutcome.NOT_ELIGIBLE
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.EligibilityCheckResponse.RuleResult
import java.util.UUID

@TestPropertySource(properties = ["app.offender-eligibility.rule-set=OFFENDER_ELIGIBILITY"])
class OffenderResourceEligibilityIT : IntegrationTestBase() {

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
            "practitioner": {"code": "N01A001", "name": {"forename": "Jane", "surname": "Smith"}, "username": "JANE.SMITH"},
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
    fetchEligibility(crn, itemise = false)
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

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `getEligibilityByCrn with itemise false for non-onboarded CRN`(itemise: Boolean) {
    offenderRepository.deleteAll()

    fetchEligibility(crn, itemise = itemise)
      .expectStatus().isOk
      .expectBody(EligibilityCheckResponse::class.java)
      .value { response ->
        assertEquals(EligibilityCheckResponseOutcome.UNKNOWN, response?.outcome)
      }

    verifySourcesFetchedOnce()
  }

  @Test
  fun `getEligibilityByCrn with itemise true returns unknown with all non manual rule results`() {
    fetchEligibility(crn, itemise = true)
      .expectStatus().isOk
      .expectBody(EligibilityCheckResponse::class.java)
      .value { response ->
        assertThat(response).isEqualTo(
          EligibilityCheckResponse(
            outcome = EligibilityCheckResponseOutcome.UNKNOWN,
            message = "John Doe may be eligible for online check ins.",
            allRules = listOf(
              RuleResult("NDELIUS", "IS_ALIVE", "DECEASED_DATE", CONTINUE),
              RuleResult("NDELIUS", "HAS_ACTIVE_EVENT", "ACTIVE_EVENT", CONTINUE),
              RuleResult("NDELIUS", "IS_CONTACT_SUSPENDED", "CONTACT_SUSPENDED", CONTINUE),
              RuleResult("TIER", "IS_TIER_MISSING", "TIER", CONTINUE),
              RuleResult("SUP-PACK", "ON_SUPERVISION_PACKAGE", "ON_PACKAGE", CONTINUE),
              RuleResult("SUP-PACK", "IS_RECALLED", "RECALLED", CONTINUE),
              RuleResult("TIER", "IS_TIER_PROVISIONAL", "PROVISIONAL", CONTINUE),
              RuleResult("SUP-PACK", "IN_FINAL_THIRD", "FINAL_THIRD", CONTINUE),
              RuleResult("NDELIUS", "IS_PRACTITIONER_ASSIGNED", "PRACTITIONER_ASSIGNED", CONTINUE),
              RuleResult("TIER", "IS_TIER_D_TO_G", "TIER", CONTINUE),
              RuleResult("ESUP", "IS_PILOT_USER", "PILOT_USER", CONTINUE),
              // only applies to Tier C
              RuleResult("ESUP", "PRACTITIONER_HAS_PILOT_CASE_TIER_C", "PRACTITIONER_PILOT", CONTINUE),
              RuleResult("TIER", "IS_TIER_C", "TIER", CONTINUE),
              // only applies on an accredited programme, which is unanswered here
              RuleResult("SUP-PACK", "IN_EARLY_ENGAGEMENT", "EARLY_ENGAGEMENT", CONTINUE),
              RuleResult("ESUP", "PRACTITIONER_HAS_PILOT_CASE_TIER_A_B", "PRACTITIONER_PILOT", CONTINUE),
            ),
          ),
        )
      }

    verifySourcesFetchedOnce()
  }

  @Test
  fun `getEligibilityByCrn with itemise true preserves ineligible outcome reached before manual rules`() {
    upstreams.stubFor(
      get(urlEqualTo("/case/$crn")).atPriority(1).willReturn(
        json(
          """
          {
            "crn": "$crn",
            "name": {"forename": "John", "surname": "Doe"},
            "dateOfBirth": "1980-01-01",
            "practitioner": {"code": "N01A001", "name": {"forename": "Jane", "surname": "Smith"}, "username": "JANE.SMITH"},
            "events": []
          }
          """.trimIndent(),
        ),
      ),
    )

    fetchEligibility(crn, itemise = true)
      .expectStatus().isOk
      .expectBody(EligibilityCheckResponse::class.java)
      .value { response ->
        assertNotNull(response)
        assertThat(response.outcome).isEqualTo(EligibilityCheckResponseOutcome.INELIGIBLE)
        assertThat(response.message).isEqualTo("No active sentence.")
        assertThat(response.allRules)
          .noneMatch { it.source == "MANUAL" }
          .element(1).isEqualTo(
            RuleResult("NDELIUS", "HAS_ACTIVE_EVENT", "ACTIVE_EVENT", NOT_ELIGIBLE, "No active sentence."),
          )
      }

    verifySourcesFetchedOnce()
  }

  @Test
  fun `getEligibilityByCrn with itemise true is ineligible when NDelius has a date of death`() {
    upstreams.stubFor(
      get(urlEqualTo("/case/$crn")).atPriority(1).willReturn(
        json(
          """
          {
            "crn": "$crn",
            "name": {"forename": "John", "surname": "Doe"},
            "dateOfBirth": "1980-01-01",
            "dateOfDeath": "2026-03-04",
            "practitioner": {"code": "N01A001", "name": {"forename": "Jane", "surname": "Smith"}, "username": "JANE.SMITH"},
            "events": [{"number": 1, "mainOffence": {"code": "001", "description": "An offence"}, "sentence": null}]
          }
          """.trimIndent(),
        ),
      ),
    )

    fetchEligibility(crn, itemise = true)
      .expectStatus().isOk
      .expectBody(EligibilityCheckResponse::class.java)
      .value { response ->
        assertNotNull(response)
        assertThat(response.outcome).isEqualTo(EligibilityCheckResponseOutcome.INELIGIBLE)
        assertThat(response.allRules).first().extracting { it.code to it.outcome }.isEqualTo("IS_ALIVE" to NOT_ELIGIBLE)
        assertThat(response.message).isEqualTo("John Doe is not eligible for online check ins because they have passed away.")
      }
  }

  @Test
  fun `checkEligibilityByCrn without answers returns unknown`() {
    checkEligibility(crn, """{"practitionerId": "SOME.PRACT"}""")
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
  fun `checkEligibilityByCrn with answers returns the deciding rule and its message`() {
    checkEligibility(
      crn,
      """{"practitionerId": "SOME.PRACT", "eligibilityAnswers": {"deviceRestriction": false, "accreditedProgramme": true, "youthSentence": false}}""",
    )
      .expectStatus().isOk
      .expectBody(EligibilityCheckResponse::class.java)
      .value { response ->
        assertThat(response).isEqualTo(
          EligibilityCheckResponse(
            outcome = EligibilityCheckResponseOutcome.INELIGIBLE,
            message = "John Doe is not eligible for online check ins because they are in Tier A or B, on an accredited programme, but are in early engagement.",
            ruleCode = "IN_EARLY_ENGAGEMENT",
          ),
        )
      }
  }

  @Test
  fun `checkEligibilityByCrn rules out someone not on a supervision package before asking anything`() {
    stubSupervisionPackage("SPNA", "STD")

    checkEligibility(crn, """{"practitionerId": "SOME.PRACT"}""")
      .expectStatus().isOk
      .expectBody(EligibilityCheckResponse::class.java)
      .value { response ->
        assertThat(response).isEqualTo(
          EligibilityCheckResponse(
            outcome = EligibilityCheckResponseOutcome.INELIGIBLE,
            message = "John Doe is not eligible for online check ins because they are not on a supervision package.",
            ruleCode = "ON_SUPERVISION_PACKAGE",
          ),
        )
      }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `checkEligibilityByCrn lets a pilot practitioner sign up Tier C`(pilotPractitioner: Boolean) {
    stubTier("C")
    // a different person, in online check ins before the pilot cut-off
    val pilotCase = offenderTemplate.copy(uuid = UUID.randomUUID(), crn = "X987653", practitionerId = "PILOT.PRACT", status = OffenderStatus.VERIFIED).toEntity()
      .also { it.inPilot = true }
    val pilotCaseId = offenderRepository.saveAndFlush(pilotCase).id
    try {
      // Judged on the practitioner allocated in NDelius. The request's practitionerId says the
      // opposite, to show it's ignored.
      stubAllocatedPractitioner(if (pilotPractitioner) "pilot.pract" else "OTHER.PRACT")
      val sentPractitionerId = if (pilotPractitioner) "OTHER.PRACT" else "PILOT.PRACT"
      checkEligibility(
        crn,
        """{"practitionerId": "$sentPractitionerId", "eligibilityAnswers": {"deviceRestriction": false, "accreditedProgramme": null, "youthSentence": null}}""",
      )
        .expectStatus().isOk
        .expectBody(EligibilityCheckResponse::class.java)
        .value { response ->
          assertNotNull(response)
          if (pilotPractitioner) {
            assertThat(response.outcome).isEqualTo(EligibilityCheckResponseOutcome.ELIGIBLE)
            assertThat(response.ruleCode).isEqualTo("PRACTITIONER_HAS_PILOT_CASE_TIER_C")
          } else {
            assertThat(response.outcome).isEqualTo(EligibilityCheckResponseOutcome.INELIGIBLE)
            assertThat(response.ruleCode).isEqualTo("IS_TIER_C")
          }
        }
    } finally {
      offenderRepository.deleteById(pilotCaseId)
    }
  }

  @Test
  fun `checkEligibilityByCrn needs no practitionerId`() {
    checkEligibility(crn, """{}""")
      .expectStatus().isOk
      .expectBody(EligibilityCheckResponse::class.java)
      .value { assertThat(it?.outcome).isEqualTo(EligibilityCheckResponseOutcome.UNKNOWN) }
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      """{"code": "N01A001", "name": {"forename": "Jane", "surname": "Smith"}}""",
      """{"code": "N01A001", "name": {"forename": "Jane", "surname": "Smith"}, "username": " "}""",
      """{"code": "N01UATU", "name": {"forename": "Unallocated", "surname": "Staff"}, "unallocated": true, "username": "UNALLOCATED"}""",
    ],
  )
  fun `checkEligibilityByCrn rules out a person whose practitioner can't sign in`(practitioner: String) {
    stubAllocatedPractitionerJson(practitioner)

    // Whoever is asking: the signed-in user isn't a stand-in for the allocated practitioner
    checkEligibility(crn, """{"practitionerId": "SIGNED.IN.USER"}""")
      .expectStatus().isOk
      .expectBody(EligibilityCheckResponse::class.java)
      .value { response ->
        assertNotNull(response)
        assertThat(response!!.outcome).isEqualTo(EligibilityCheckResponseOutcome.INELIGIBLE)
        assertThat(response.ruleCode).isEqualTo("IS_PRACTITIONER_ASSIGNED")
        assertThat(response.message).isEqualTo(
          "John Doe must have probation practitioner assigned to them before they can be set up to use online check ins",
        )
      }
  }

  private fun stubAllocatedPractitioner(username: String) = stubAllocatedPractitionerJson(
    """{"code": "N01A001", "name": {"forename": "Jane", "surname": "Smith"}, "username": "$username"}""",
  )

  private fun stubAllocatedPractitionerJson(practitioner: String) {
    upstreams.stubFor(
      get(urlEqualTo("/case/$crn")).atPriority(1).willReturn(
        json(
          """
          {
            "crn": "$crn",
            "name": {"forename": "John", "surname": "Doe"},
            "dateOfBirth": "1980-01-01",
            "practitioner": $practitioner,
            "events": [{"number": 1, "mainOffence": {"code": "001", "description": "An offence"}, "sentence": null}]
          }
          """.trimIndent(),
        ),
      ),
    )
  }

  @Test
  fun `checkEligibilityByCrn returns 404 for a CRN unknown to NDelius`() {
    checkEligibility("X000000", """{"practitionerId": "SOME.PRACT"}""")
      .expectStatus().isNotFound
  }

  private fun checkEligibility(crn: CRN, body: String) = webTestClient.post()
    .uri("/v2/offenders/crn/$crn/eligibility")
    .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(body)
    .exchange()

  private fun stubTier(tier: String) {
    upstreams.stubFor(
      get(urlEqualTo("/v3/crn/$crn/tier")).atPriority(1).willReturn(
        json(
          """
          {
            "tierScore": "$tier",
            "calculationId": "11111111-2222-3333-4444-555555555555",
            "calculationDate": "2026-10-01T08:15:30.123",
            "changeReason": null,
            "provisional": false
          }
          """.trimIndent(),
        ),
      ),
    )
  }

  private fun stubSupervisionPackage(packageCode: String, phaseCode: String) {
    upstreams.stubFor(
      get(urlEqualTo("/frontend-context/$crn")).atPriority(1).willReturn(
        json(
          """
          {
            "currentPhase": {
              "supervisionPackage": {"code": "$packageCode", "description": "package"},
              "phase": {"code": "$phaseCode", "description": "phase"}
            },
            "context": {"recallStatus": null, "sentences": []}
          }
          """.trimIndent(),
        ),
      ),
    )
  }

  private fun fetchEligibility(crn: CRN, itemise: Boolean) = webTestClient.get()
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
