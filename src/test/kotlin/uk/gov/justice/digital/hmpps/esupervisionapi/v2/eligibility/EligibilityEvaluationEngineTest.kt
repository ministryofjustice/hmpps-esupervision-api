package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityEvaluationEngine.Companion.DEFAULT_RULE_SET
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class EligibilityEvaluationEngineTest {

  private val ruleRepository: EligibilityRuleRepository = mock()
  private val providerRegistry: EligibilityDataProviderRegistry = mock()
  private val engine = EligibilityEvaluationEngine(ruleRepository, providerRegistry, "MOCKED", 2000L)

  @AfterEach
  fun tearDown() {
    reset(ruleRepository, providerRegistry)
  }

  private fun rule(
    code: String,
    order: Double,
    source: String,
    dataPoint: String,
    operator: EligibilityRuleOperator,
    outcomeOnMatch: EligibilityRuleOutcome = EligibilityRuleOutcome.CONTINUE,
    messageOnMatch: String? = null,
    outcomeOnNoMatch: EligibilityRuleOutcome = EligibilityRuleOutcome.NOT_ELIGIBLE,
    messageOnNoMatch: String? = "not eligible: $code",
  ) = OffenderEligibilityRule(
    ruleSet = EligibilityEvaluationEngine.DEFAULT_RULE_SET,
    ruleOrder = order,
    code = code,
    question = code,
    source = source,
    dataPoint = dataPoint,
    operator = operator,
    outcomeOnMatch = outcomeOnMatch,
    messageOnMatch = messageOnMatch,
    outcomeOnNoMatch = outcomeOnNoMatch,
    messageOnNoMatch = messageOnNoMatch,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
  )

  private fun mockProvider(sourceKey: String, data: Map<String, Any?>): EligibilityDataProvider {
    val provider: EligibilityDataProvider = mock()
    whenever(provider.sourceKey).thenReturn(sourceKey)
    whenever(provider.fetch(org.mockito.kotlin.any())).thenReturn(CompletableFuture.completedFuture(data))
    whenever(providerRegistry.get(sourceKey)).thenReturn(provider)
    return provider
  }

  @Test
  fun `all rules continue - eligible with no message`() {
    val ndeliusRule = rule("IS_ALIVE", 1.0, "NDELIUS", "DECEASED_DATE", EligibilityRuleOperator.IS_NULL)
    whenever(ruleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(EligibilityEvaluationEngine.DEFAULT_RULE_SET))
      .thenReturn(listOf(ndeliusRule))
    mockProvider("NDELIUS", mapOf("DECEASED_DATE" to null))

    val result = engine.evaluate("X123456", DEFAULT_RULE_SET).join()

    assertEquals(EligibilityCheckOutcome.ELIGIBLE, result.outcome)
    assertNull(result.message)
    assertNull(result.triggeredRuleCode)
  }

  @Test
  fun `terminal ELIGIBLE outcome short-circuits remaining rules`() {
    val firstRule = rule(
      "SHORTCUT",
      1.0,
      "NDELIUS",
      "DECEASED_DATE",
      EligibilityRuleOperator.IS_NULL,
      outcomeOnMatch = EligibilityRuleOutcome.ELIGIBLE,
      messageOnMatch = "eligible early",
      messageOnNoMatch = null,
    )
    val secondRule = rule("NEVER_REACHED", 2.0, "NOMIS", "RECALL_STATUS", EligibilityRuleOperator.IS_NULL)
    whenever(ruleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(EligibilityEvaluationEngine.DEFAULT_RULE_SET))
      .thenReturn(listOf(firstRule, secondRule))
    mockProvider("NDELIUS", mapOf("DECEASED_DATE" to null))
    val nomisProvider = mockProvider("NOMIS", mapOf("RECALL_STATUS" to null))

    val result = engine.evaluate("X123456", DEFAULT_RULE_SET).join()

    assertEquals(EligibilityCheckOutcome.ELIGIBLE, result.outcome)
    assertEquals("eligible early", result.message)
    assertEquals("SHORTCUT", result.triggeredRuleCode)
    verify(nomisProvider, never()).fetch(org.mockito.kotlin.any())
  }

  @Test
  fun `terminal NOT_ELIGIBLE outcome stops evaluation with message`() {
    val rule = rule("IS_ALIVE", 1.0, "NDELIUS", "DECEASED_DATE", EligibilityRuleOperator.IS_NULL)
    whenever(ruleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(EligibilityEvaluationEngine.DEFAULT_RULE_SET))
      .thenReturn(listOf(rule))
    mockProvider("NDELIUS", mapOf("DECEASED_DATE" to "2020-01-01"))

    val result = engine.evaluate("X123456", DEFAULT_RULE_SET).join()

    assertEquals(EligibilityCheckOutcome.INELIGIBLE, result.outcome)
    assertEquals("not eligible: IS_ALIVE", result.message)
    assertEquals("IS_ALIVE", result.triggeredRuleCode)
  }

  @Test
  fun `two rules sharing a source only fetch that source once`() {
    val first = rule("ALIVE", 1.0, "NDELIUS", "DECEASED_DATE", EligibilityRuleOperator.IS_NULL)
    val second = rule("SENTENCE", 2.0, "NDELIUS", "ACTIVE_EVENT", EligibilityRuleOperator.IS_NOT_NULL)
    whenever(ruleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(EligibilityEvaluationEngine.DEFAULT_RULE_SET))
      .thenReturn(listOf(first, second))
    val provider = mockProvider("NDELIUS", mapOf("DECEASED_DATE" to null, "ACTIVE_EVENT" to "ACTIVE"))

    val result = engine.evaluate("X123456", DEFAULT_RULE_SET).join()

    assertEquals(EligibilityCheckOutcome.ELIGIBLE, result.outcome)
    verify(provider, times(1)).fetch(org.mockito.kotlin.any())
  }

  @Test
  fun `source fetch failure surfaces as EligibilityDataUnavailableException`() {
    val rule = rule("IS_ALIVE", 1.0, "NDELIUS", "DECEASED_DATE", EligibilityRuleOperator.IS_NULL)
    whenever(ruleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(EligibilityEvaluationEngine.DEFAULT_RULE_SET))
      .thenReturn(listOf(rule))
    val provider: EligibilityDataProvider = mock()
    whenever(provider.sourceKey).thenReturn("NDELIUS")
    val failedFuture = CompletableFuture<Map<String, Any?>>()
    failedFuture.completeExceptionally(RuntimeException("NDelius unavailable"))
    whenever(provider.fetch(org.mockito.kotlin.any())).thenReturn(failedFuture)
    whenever(providerRegistry.get("NDELIUS")).thenReturn(provider)

    val future = engine.evaluate("X123456", DEFAULT_RULE_SET)
    val exception = org.junit.jupiter.api.Assertions.assertThrows(CompletionException::class.java) { future.join() }

    val cause = exception.cause
    assertTrue(cause is EligibilityDataUnavailableException)
    assertTrue(cause?.message?.contains("IS_ALIVE") == true)
  }

  @Test
  fun `pre-populated cache is used as-is and its source is never fetched`() {
    val ndeliusRule = rule("IS_ALIVE", 1.0, "NDELIUS", "DECEASED_DATE", EligibilityRuleOperator.IS_NULL)
    whenever(ruleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(EligibilityEvaluationEngine.DEFAULT_RULE_SET))
      .thenReturn(listOf(ndeliusRule))
    val prePopulatedCache = mapOf("NDELIUS" to CompletableFuture.completedFuture(mapOf<String, Any?>("DECEASED_DATE" to null)))

    val result = engine.evaluate("X123456", prePopulatedCache = prePopulatedCache, ruleSet = DEFAULT_RULE_SET).join()

    assertEquals(EligibilityCheckOutcome.ELIGIBLE, result.outcome)
    verify(providerRegistry, never()).get(org.mockito.kotlin.any())
  }

  @Test
  fun `pre-populated cache falls back to the provider for a missing source, fetched once`() {
    val first = rule("RECALLED", 1.0, "NOMIS", "RECALL_STATUS", EligibilityRuleOperator.IS_NULL)
    val second = rule("SENTENCE", 2.0, "NOMIS", "ACTIVE_EVENT", EligibilityRuleOperator.IS_NOT_NULL)
    whenever(ruleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(EligibilityEvaluationEngine.DEFAULT_RULE_SET))
      .thenReturn(listOf(first, second))
    val provider = mockProvider("NOMIS", mapOf("RECALL_STATUS" to null, "ACTIVE_EVENT" to "ACTIVE"))

    val result = engine.evaluate("X123456", prePopulatedCache = emptyMap(), ruleSet = DEFAULT_RULE_SET).join()

    assertEquals(EligibilityCheckOutcome.ELIGIBLE, result.outcome)
    verify(provider, times(1)).fetch(org.mockito.kotlin.any())
  }
}
