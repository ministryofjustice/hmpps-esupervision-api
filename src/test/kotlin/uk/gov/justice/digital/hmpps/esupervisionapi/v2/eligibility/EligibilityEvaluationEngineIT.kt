package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EligibilityAnswers
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityCheckOutcome.ELIGIBLE
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityCheckOutcome.INELIGIBLE
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityEvaluationEngine.Companion.DEFAULT_RULE_SET
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

class EligibilityEvaluationEngineIT : IntegrationTestBase() {

  @Autowired
  private lateinit var eligibilityEvaluationEngine: EligibilityEvaluationEngine

  @Autowired
  private lateinit var eligibilityRuleRepository: EligibilityRuleRepository

  @Test
  fun `verify we query the right rules`() {
    val rules = eligibilityRuleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(DEFAULT_RULE_SET)

    assertThat(rules).extracting<String> { it.code }
      .containsExactly(
        "HAS_ACTIVE_EVENT",
        "IS_CONTACT_SUSPENDED",
        "ON_SUPERVISION_PACKAGE",
        "IS_RECALLED",
        "IS_TIER_MISSING",
        "IS_TIER_PROVISIONAL",
        "IN_FINAL_THIRD",
        "IS_PRACTITIONER_ASSIGNED",
        "HAS_DEVICE_RESTRICTION",
        "IS_TIER_D_TO_G",
        "IS_PILOT_USER",
        "PRACTITIONER_HAS_PILOT_CASE_TIER_C",
        "IS_TIER_C",
        "ON_YOUTH_SENTENCE",
        "IN_EARLY_ENGAGEMENT",
        "PRACTITIONER_HAS_PILOT_CASE_TIER_A_B",
        "ON_ACCREDITED_PROGRAMME",
      )

    assertThat(rules.map { it.deliusOutcomeCode })
      .containsExactly(
        "ESPNA",
        "ESPRS",
        null, // ON_SUPERVISION_PACKAGE: NDelius outcome code not agreed yet
        "ESPRC",
        "ESPME",
        "ESPIT",
        "ESPFT",
        "ESPNP",
        "ESPDI",
        null,
        null,
        null, // the pilot practitioner rules only ever make someone eligible
        null,
        null,
        "ESPEE",
        null,
        "ESPAP",
      )

    val pilotRules = eligibilityRuleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc("PILOT_2026_08")
    assertThat(pilotRules.filter { it.deliusOutcomeCode == null }).isEmpty()
  }

  @Test
  fun `evaluates rules from the database using the pre-populated fetch cache`() {
    val prePopulatedCache: MutableMap<String, CompletableFuture<Map<String, Any?>>> = mutableMapOf(
      "NDELIUS" to CompletableFuture.completedFuture(
        mapOf(
          "ACTIVE_EVENT" to Any(),
          "PRACTITIONER_ASSIGNED" to true,
          "CONTACT_SUSPENDED" to false,
        ),
      ),
      "SUP-PACK" to CompletableFuture.completedFuture(
        mapOf(
          "ON_PACKAGE" to true,
          "RECALLED" to false,
          "FINAL_THIRD" to false,
          "EARLY_ENGAGEMENT" to false,
        ),
      ),
      "TIER" to CompletableFuture.completedFuture(
        mapOf(
          "TIER" to "B",
          "PROVISIONAL" to "false",
        ),
      ),
      "ESUP" to CompletableFuture.completedFuture(
        mapOf(
          "PILOT_USER" to true,
          "PRACTITIONER_PILOT" to false,
        ),
      ),
      "MANUAL" to CompletableFuture.completedFuture(
        mapOf(
          "DEVICE_RESTRICTION" to false,
          "ACCREDITED_PROGRAMME" to false,
          "YOUTH_SENTENCE" to false,
        ),
      ),
    )

    val result = eligibilityEvaluationEngine
      .evaluate("X123456", ruleSet = DEFAULT_RULE_SET, prePopulatedCache = prePopulatedCache)
      .join()

    assertThat(result.outcome).isEqualTo(EligibilityCheckOutcome.ELIGIBLE)
    assertThat(result.triggeredRuleCode).isEqualTo("IS_PILOT_USER")

    prePopulatedCache["TIER"] = CompletableFuture.completedFuture(mapOf("TIER" to "C", "PROVISIONAL" to "false"))
    prePopulatedCache["ESUP"] = CompletableFuture.completedFuture(mapOf("PILOT_USER" to false, "PRACTITIONER_PILOT" to false))
    val resultC = eligibilityEvaluationEngine
      .evaluate("X123456", DEFAULT_RULE_SET, prePopulatedCache)
      .join()

    assertThat(resultC.outcome).isEqualTo(EligibilityCheckOutcome.INELIGIBLE)
    assertThat(resultC.dashboardMessage).isEqualTo("{{offender}} is not eligible for online check ins because they are in Tier C.")
    assertThat(resultC.triggeredRuleCode).isEqualTo("IS_TIER_C")

    // a practitioner who has had a pilot case can sign up Tier C
    prePopulatedCache["ESUP"] = CompletableFuture.completedFuture(mapOf("PILOT_USER" to false, "PRACTITIONER_PILOT" to true))
    val resultPilotPractitioner = eligibilityEvaluationEngine
      .evaluate("X123456", DEFAULT_RULE_SET, prePopulatedCache)
      .join()

    assertThat(resultPilotPractitioner.outcome).isEqualTo(EligibilityCheckOutcome.ELIGIBLE)
    assertThat(resultPilotPractitioner.triggeredRuleCode).isEqualTo("PRACTITIONER_HAS_PILOT_CASE_TIER_C")

    // nobody off a supervision package is eligible
    prePopulatedCache["SUP-PACK"] = CompletableFuture.completedFuture(
      mapOf("ON_PACKAGE" to false, "RECALLED" to false, "FINAL_THIRD" to false, "EARLY_ENGAGEMENT" to false),
    )
    val resultNoPackage = eligibilityEvaluationEngine
      .evaluate("X123456", DEFAULT_RULE_SET, prePopulatedCache)
      .join()

    assertThat(resultNoPackage.outcome).isEqualTo(EligibilityCheckOutcome.INELIGIBLE)
    assertThat(resultNoPackage.triggeredRuleCode).isEqualTo("ON_SUPERVISION_PACKAGE")
  }

  /**
   * The full rule set after Tier D-G must decide as the manage check ins UI's own rules did
   * (nextAfterEligibilityCheck / nextAfterPilotCheck in that repo).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("decisionTable")
  fun `full rules decide tiers A to C as the UI does`(
    case: String,
    tier: String,
    answers: EligibilityAnswers,
    earlyEngagement: Boolean,
    practitionerPilot: Boolean,
    expected: EligibilityCheckOutcome,
    expectedRule: String,
  ) {
    val cache = mapOf(
      "NDELIUS" to CompletableFuture.completedFuture(
        mapOf<String, Any?>("ACTIVE_EVENT" to "event", "CONTACT_SUSPENDED" to false, "PRACTITIONER_ASSIGNED" to true),
      ),
      "SUP-PACK" to CompletableFuture.completedFuture(
        mapOf<String, Any?>("ON_PACKAGE" to true, "RECALLED" to false, "FINAL_THIRD" to false, "EARLY_ENGAGEMENT" to earlyEngagement),
      ),
      "TIER" to CompletableFuture.completedFuture(mapOf<String, Any?>("TIER" to tier, "PROVISIONAL" to false)),
      "ESUP" to CompletableFuture.completedFuture(mapOf<String, Any?>("PILOT_USER" to false, "PRACTITIONER_PILOT" to practitionerPilot)),
      "MANUAL" to CompletableFuture.completedFuture(answers.eligibilityData()),
    )

    val result = eligibilityEvaluationEngine.evaluate("X123456", DEFAULT_RULE_SET, cache).join()

    assertThat(result.outcome).describedAs(case).isEqualTo(expected)
    assertThat(result.triggeredRuleCode).describedAs(case).isEqualTo(expectedRule)
  }

  companion object {
    private fun ab(programme: Boolean, youth: Boolean) = EligibilityAnswers(accreditedProgramme = programme, deviceRestriction = false, youthSentence = youth)
    private val tierC = EligibilityAnswers(accreditedProgramme = null, deviceRestriction = false, youthSentence = null)

    // The nightly sync has no practitioner answers and assumes these for everyone
    private val syncAnswers = EligibilityAnswers(accreditedProgramme = true, deviceRestriction = false, youthSentence = false)

    @JvmStatic
    fun decisionTable(): Stream<Arguments> = Stream.of(
      Arguments.of("A/B on programme", "B", ab(true, false), false, false, ELIGIBLE, "IN_EARLY_ENGAGEMENT"),
      Arguments.of("A/B on programme, pilot practitioner", "A", ab(true, false), false, true, ELIGIBLE, "IN_EARLY_ENGAGEMENT"),
      Arguments.of("A/B on programme, youth sentence, pilot practitioner", "B", ab(true, true), false, true, INELIGIBLE, "ON_YOUTH_SENTENCE"),
      Arguments.of("A/B on programme, early engagement, pilot practitioner", "B", ab(true, false), true, true, INELIGIBLE, "IN_EARLY_ENGAGEMENT"),
      Arguments.of("A/B not on programme, pilot practitioner", "B", ab(false, false), false, true, ELIGIBLE, "PRACTITIONER_HAS_PILOT_CASE_TIER_A_B"),
      Arguments.of("A/B not on programme, youth sentence, pilot practitioner", "B", ab(false, true), false, true, ELIGIBLE, "PRACTITIONER_HAS_PILOT_CASE_TIER_A_B"),
      Arguments.of("A/B not on programme, early engagement, pilot practitioner", "A", ab(false, false), true, true, ELIGIBLE, "PRACTITIONER_HAS_PILOT_CASE_TIER_A_B"),
      Arguments.of("A/B not on programme", "B", ab(false, false), false, false, INELIGIBLE, "ON_ACCREDITED_PROGRAMME"),
      Arguments.of("C, pilot practitioner", "C", tierC, false, true, ELIGIBLE, "PRACTITIONER_HAS_PILOT_CASE_TIER_C"),
      Arguments.of("C", "C", tierC, false, false, INELIGIBLE, "IS_TIER_C"),
      Arguments.of("C, nightly sync answers", "C", syncAnswers, false, false, INELIGIBLE, "IS_TIER_C"),
      Arguments.of("C, nightly sync answers, pilot practitioner", "C", syncAnswers, true, true, ELIGIBLE, "PRACTITIONER_HAS_PILOT_CASE_TIER_C"),
      Arguments.of("D", "D", tierC, false, false, ELIGIBLE, "IS_TIER_D_TO_G"),
    )
  }
}
