package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface OffenderEligibilityCheckRepository : JpaRepository<OffenderEligibilityCheck, Long> {
  /**
   * Upserts the (offender, ruleSet) row - the sync job's re-runs overwrite the previous
   * outcome/checkedAt instead of accumulating history, giving idempotent job runs.
   */
  @Query(
    """
    insert into offender_eligibility_check (offender_id, rule_set, outcome, message, triggered_rule_code, checked_at, created_at, updated_at)
    values (:offenderId, :ruleSet, cast(:outcome as text)::eligibility_check_outcome, :message, :triggeredRuleCode, :checkedAt, now(), now())
    on conflict (offender_id, rule_set) do update set
      outcome = excluded.outcome,
      message = excluded.message,
      triggered_rule_code = excluded.triggered_rule_code,
      checked_at = excluded.checked_at,
      updated_at = now()
    """,
    nativeQuery = true,
  )
  @Modifying
  fun upsert(
    offenderId: Long,
    ruleSet: String,
    outcome: String,
    message: String?,
    triggeredRuleCode: String?,
    checkedAt: Instant,
  ): Int
}
