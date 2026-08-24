package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class EligibilityCheckOutcome {
  ELIGIBLE,
  INELIGIBLE,
}

data class EligibilityResult(
  val outcome: EligibilityCheckOutcome,
  val message: String?,
  /** Code of the rule that produced a terminal outcome, or null if every rule passed. */
  val triggeredRuleCode: String?,
)

/** Thrown when a rule's source data can't be fetched - the engine throws rather than
 *  silently treating the offender as eligible/ineligible from a data gap. */
class EligibilityDataUnavailableException(ruleCode: String, source: String, cause: Throwable) : RuntimeException("Could not evaluate eligibility rule '$ruleCode': source '$source' unavailable", cause)

/**
 * Evaluates the ordered offender eligibility rule set against one CRN, fetching each rule's
 * source data lazily (only when a rule reached in the chain needs it) and memoizing repeated
 * sources within a single evaluation run.
 */
@Service
class EligibilityEvaluationEngine(
  private val ruleRepository: EligibilityRuleRepository,
  private val providerRegistry: EligibilityDataProviderRegistry,
  @Value($$"${app.offender-eligibility.rule-set}") val activeRuleSet: String,
  @Value($$"${app.offender-eligibility.source-timeout-ms:2000}") val sourceTimeoutMs: Long,
) {
  typealias DataSource = String // e.g. "NDELIUS", "NOMIS" etc

  /** We use get-or-fetch to hide whether sources are resolved lazily or supplied up front. */
  private sealed interface FetchCache {
    fun getOrFetch(source: DataSource, crn: CRN): CompletableFuture<Map<String, Any?>>
  }

  /** Starts empty; fetches each source on demand. */
  private class LazyFetchCache(private val providerRegistry: EligibilityDataProviderRegistry) : FetchCache {
    private val cache = ConcurrentHashMap<DataSource, CompletableFuture<Map<String, Any?>>>()

    override fun getOrFetch(source: DataSource, crn: CRN): CompletableFuture<Map<String, Any?>> = cache.computeIfAbsent(source) { providerRegistry.get(source).fetch(crn) }
  }

  /**
   * Uses [supplied] as-is - never copied/wrapped - so a fully resolved cache needs no
   * concurrent-safe mutation. A source missing from [supplied] falls back to the provider registry,
   * memoized per evaluation run so rules sharing a missing source still only fetch once.
   */
  private class PrePopulatedFetchCache(
    private val supplied: Map<DataSource, CompletableFuture<Map<String, Any?>>>,
    private val providerRegistry: EligibilityDataProviderRegistry,
  ) : FetchCache {
    private val fallback = ConcurrentHashMap<DataSource, CompletableFuture<Map<String, Any?>>>()

    override fun getOrFetch(source: DataSource, crn: CRN): CompletableFuture<Map<String, Any?>> = supplied[source] ?: fallback.computeIfAbsent(source) { providerRegistry.get(source).fetch(crn) }
  }

  fun evaluate(crn: CRN, ruleSet: String): CompletableFuture<EligibilityResult> = evaluateFrom(
    ruleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(ruleSet),
    0,
    crn,
    LazyFetchCache(providerRegistry),
  )

  /**
   * [prePopulatedCache] is used as-is; a partial or empty map is valid - any source it doesn't
   * cover is fetched lazily and memoized for this call.
   */
  fun evaluate(
    crn: CRN,
    ruleSet: String,
    prePopulatedCache: Map<DataSource, CompletableFuture<Map<String, Any?>>>,
  ): CompletableFuture<EligibilityResult> = evaluateFrom(
    ruleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(ruleSet),
    0,
    crn,
    PrePopulatedFetchCache(prePopulatedCache, providerRegistry),
  )

  private fun evaluateFrom(
    rules: List<OffenderEligibilityRule>,
    index: Int,
    crn: String,
    fetchCache: FetchCache,
  ): CompletableFuture<EligibilityResult> {
    if (index >= rules.size) {
      return CompletableFuture.completedFuture(EligibilityResult(outcome = EligibilityCheckOutcome.ELIGIBLE, message = null, triggeredRuleCode = null))
    }
    val rule = rules[index]
    val sourceFuture = fetchCache
      .getOrFetch(rule.source, crn)
      .orTimeout(sourceTimeoutMs, TimeUnit.MILLISECONDS)

    return sourceFuture
      .thenCompose { sourceData ->
        val matched = EligibilityConditionEvaluator.evaluate(rule.operator, sourceData[rule.dataPoint], rule.comparisonValue)
        val outcome = if (matched) rule.outcomeOnMatch else rule.outcomeOnNoMatch
        val message = if (matched) rule.messageOnMatch else rule.messageOnNoMatch
        when (outcome) {
          EligibilityRuleOutcome.CONTINUE -> evaluateFrom(rules, index + 1, crn, fetchCache)
          EligibilityRuleOutcome.ELIGIBLE -> CompletableFuture.completedFuture(EligibilityResult(EligibilityCheckOutcome.ELIGIBLE, message, rule.code))
          EligibilityRuleOutcome.NOT_ELIGIBLE -> CompletableFuture.completedFuture(EligibilityResult(EligibilityCheckOutcome.INELIGIBLE, message, rule.code))
        }
      }
      .exceptionallyCompose { throwable ->
        CompletableFuture.failedFuture(EligibilityDataUnavailableException(rule.code, rule.source, throwable))
      }
  }

  companion object {
    const val DEFAULT_RULE_SET = "OFFENDER_ELIGIBILITY"
    const val PILOT_RULE_SET = "PILOT_2026_08"
  }
}
