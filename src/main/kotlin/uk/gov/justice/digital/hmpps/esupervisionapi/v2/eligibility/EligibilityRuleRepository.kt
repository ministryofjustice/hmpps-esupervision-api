package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface EligibilityRuleRepository : JpaRepository<OffenderEligibilityRule, Long> {
  fun findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(ruleSet: String): List<OffenderEligibilityRule>
}
