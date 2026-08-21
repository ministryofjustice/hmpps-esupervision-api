package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

/**
 * Evaluates a rule's operator against a fetched data-point value. New operators (numeric/date
 * comparisons, etc.) are added here as a new enum value + `when` branch - the compiler's
 * exhaustiveness check is the extension point; no other eligibility code needs to change.
 */
object EligibilityConditionEvaluator {
  fun evaluate(operator: EligibilityRuleOperator, value: Any?, comparisonValue: String?): Boolean = when (operator) {
    EligibilityRuleOperator.IS_NULL -> value == null
    EligibilityRuleOperator.IS_NOT_NULL -> value != null
    EligibilityRuleOperator.EQUALS -> value?.toString() == comparisonValue
  }
}
