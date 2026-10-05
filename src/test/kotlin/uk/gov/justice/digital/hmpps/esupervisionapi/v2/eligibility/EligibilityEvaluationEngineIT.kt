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
        "IS_RECALLED",
        "IS_TIER_PROVISIONAL",
        "IN_FINAL_THIRD",
        "IS_PRACTITIONER_ASSIGNED",
        "IS_TIER_D_TO_G",
        "IS_TIER_C",
        "IN_EARLY_ENGAGEMENT",
      )
  }

  @Test
  fun `evaluates rules from the database using the pre-populated fetch cache`() {
    val prePopulatedCache: MutableMap<String, CompletableFuture<Map<String, Any?>>> = mutableMapOf(
      "NDELIUS" to CompletableFuture.completedFuture(
        mapOf(
          "ACTIVE_EVENT" to Any(),
          "PRACTITIONER_ASSIGNED" to true,
        ),
      ),
      "SUP-PACK" to CompletableFuture.completedFuture(
        mapOf(
          "RECALLED" to false,
          "FINAL_THIRD" to false,
          "EARLY_ENGAGEMENT" to false,
        ),
      ),
      "TIER" to CompletableFuture.completedFuture<Map<String, Any?>>(
        mapOf(
          "TIER" to "B",
          "PROVISIONAL" to "false",
        ),
      ),
    )

    val result = eligibilityEvaluationEngine
      .evaluate("X123456", ruleSet = DEFAULT_RULE_SET, prePopulatedCache = prePopulatedCache)
      .join()

    assertThat(result.outcome).isEqualTo(EligibilityCheckOutcome.ELIGIBLE)
    assertThat(result.triggeredRuleCode).isEqualTo("IN_EARLY_ENGAGEMENT")

    prePopulatedCache["TIER"] = CompletableFuture.completedFuture(mapOf("TIER" to "C", "PROVISIONAL" to "false"))
    val resultC = eligibilityEvaluationEngine
      .evaluate("X123456", DEFAULT_RULE_SET, prePopulatedCache)
      .join()

    assertThat(resultC.outcome).isEqualTo(EligibilityCheckOutcome.INELIGIBLE)
    assertThat(resultC.message).isEqualTo("{{offender}} is not eligible for online check ins because they are in Tier C.")
    assertThat(resultC.triggeredRuleCode).isEqualTo("IS_TIER_C")
  }
}
