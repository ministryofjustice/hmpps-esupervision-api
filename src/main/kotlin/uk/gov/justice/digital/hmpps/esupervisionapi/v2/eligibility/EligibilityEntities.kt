package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.persistence.V2BaseEntity
import java.time.Instant

enum class EligibilityRuleOperator {
  IS_NULL,
  IS_NOT_NULL,
  EQUALS,
}

enum class EligibilityRuleOutcome {
  CONTINUE,
  ELIGIBLE,
  NOT_ELIGIBLE,
}

/**
 * One row of the offender eligibility decision table: pulls [dataPoint] from [source],
 * evaluates it with [operator]/[comparisonValue], and either continues to the next rule
 * (by [ruleOrder]) or terminates evaluation with an eligible/not-eligible outcome + message.
 */
@Entity
@Table(name = "offender_eligibility_rule")
open class OffenderEligibilityRule(
  @Column(name = "rule_set", nullable = false, columnDefinition = "varchar(64) default 'OFFENDER_ELIGIBILITY'")
  open var ruleSet: String,

  @Column(name = "rule_order", nullable = false)
  open var ruleOrder: Double,

  @Column(name = "code", nullable = false, columnDefinition = "varchar(64)")
  open var code: String,

  @Column(name = "question", nullable = false, columnDefinition = "text")
  open var question: String,

  @Column(name = "source", nullable = false, columnDefinition = "varchar(32)")
  open var source: String,

  @Column(name = "data_point", nullable = false, columnDefinition = "varchar(128)")
  open var dataPoint: String,

  @Column(name = "operator", nullable = false)
  @Enumerated(EnumType.STRING)
  open var operator: EligibilityRuleOperator,

  @Column(name = "comparison_value", nullable = true, columnDefinition = "text")
  open var comparisonValue: String? = null,

  @Column(name = "outcome_on_match", nullable = false)
  @Enumerated(EnumType.STRING)
  open var outcomeOnMatch: EligibilityRuleOutcome,

  @Column(name = "message_on_match", nullable = true, columnDefinition = "text")
  open var messageOnMatch: String? = null,

  @Column(name = "outcome_on_no_match", nullable = false)
  @Enumerated(EnumType.STRING)
  open var outcomeOnNoMatch: EligibilityRuleOutcome,

  @Column(name = "message_on_no_match", nullable = true, columnDefinition = "text")
  open var messageOnNoMatch: String? = null,

  @Column(name = "enabled", nullable = false)
  open var enabled: Boolean = true,

  @Column(name = "comment", nullable = true, columnDefinition = "text")
  open var comment: String? = null,

  @Column(name = "created_at", nullable = false)
  open var createdAt: Instant,

  @Column(name = "updated_at", nullable = false)
  open var updatedAt: Instant,
) : V2BaseEntity()
