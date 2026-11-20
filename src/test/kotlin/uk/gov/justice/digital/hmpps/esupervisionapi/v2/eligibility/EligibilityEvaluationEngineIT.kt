package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityEvaluationEngine.Companion.DEFAULT_RULE_SET
import java.util.concurrent.CompletableFuture

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
        "IS_TIER_MISSING",
        "ON_SUPERVISION_PACKAGE",
        "IS_RECALLED",
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

    // The code NDelius records on the setup contact (ESPCHS) when the nightly sync stops check ins
    // because of the rule. Rules that can only make someone eligible have none.
    assertThat(rules.associate { it.code to it.deliusOutcomeCode })
      .containsExactlyEntriesOf(
        linkedMapOf(
          "HAS_ACTIVE_EVENT" to "ESPNA",
          "IS_CONTACT_SUSPENDED" to "ESPRS",
          "IS_TIER_MISSING" to "ESPME",
          "ON_SUPERVISION_PACKAGE" to "ESPSP",
          "IS_RECALLED" to "ESPRC",
          "IS_TIER_PROVISIONAL" to "ESPIT",
          "IN_FINAL_THIRD" to "ESPFT",
          "IS_PRACTITIONER_ASSIGNED" to "ESPNP",
          "HAS_DEVICE_RESTRICTION" to "ESPDI",
          "IS_TIER_D_TO_G" to null,
          "IS_PILOT_USER" to null,
          "PRACTITIONER_HAS_PILOT_CASE_TIER_C" to null,
          "IS_TIER_C" to "ESPIT",
          "ON_YOUTH_SENTENCE" to "ESPYS",
          "IN_EARLY_ENGAGEMENT" to "ESPEE",
          "PRACTITIONER_HAS_PILOT_CASE_TIER_A_B" to null,
          "ON_ACCREDITED_PROGRAMME" to "ESPAP",
        ),
      )

    val pilotRules = eligibilityRuleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc("PILOT_2026_08")
    assertThat(pilotRules.filter { it.deliusOutcomeCode == null }).isEmpty()
  }

  @Test
  fun `every rule that can stop check ins tells NDelius why`() {
    listOf(DEFAULT_RULE_SET, "PILOT_2026_08").forEach { ruleSet ->
      val rulingOut = eligibilityRuleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(ruleSet)
        .filter { it.outcomeOnMatch == EligibilityRuleOutcome.NOT_ELIGIBLE || it.outcomeOnNoMatch == EligibilityRuleOutcome.NOT_ELIGIBLE }

      assertThat(rulingOut).describedAs(ruleSet).isNotEmpty()
      assertThat(rulingOut.filter { it.deliusOutcomeCode == null }.map { it.code }).describedAs(ruleSet).isEmpty()
    }
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
}
