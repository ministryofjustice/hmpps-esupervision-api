package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EligibilityConditionEvaluatorTest {

  @Test
  fun `IS_NULL matches only a null value`() {
    assertTrue(EligibilityConditionEvaluator.evaluate(EligibilityRuleOperator.IS_NULL, null, null))
    assertFalse(EligibilityConditionEvaluator.evaluate(EligibilityRuleOperator.IS_NULL, "2020-01-01", null))
  }

  @Test
  fun `IS_NOT_NULL matches only a non-null value`() {
    assertTrue(EligibilityConditionEvaluator.evaluate(EligibilityRuleOperator.IS_NOT_NULL, "ACTIVE", null))
    assertFalse(EligibilityConditionEvaluator.evaluate(EligibilityRuleOperator.IS_NOT_NULL, null, null))
  }

  @Test
  fun `EQUALS compares the value's string form against comparisonValue`() {
    assertTrue(EligibilityConditionEvaluator.evaluate(EligibilityRuleOperator.EQUALS, "RECALLED", "RECALLED"))
    assertFalse(EligibilityConditionEvaluator.evaluate(EligibilityRuleOperator.EQUALS, "RECALLED", "OTHER"))
    assertFalse(EligibilityConditionEvaluator.evaluate(EligibilityRuleOperator.EQUALS, null, "RECALLED"))
  }
}
