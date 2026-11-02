package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.ResourceNotFoundException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/**
 * A shim around the [EligibilityEvaluationEngine] meant to hide the differences between the
 * pilot eligibility code paths and the rule-based eligibility code paths in HTTP resources.
 *
 *  The message template in returned value will be already evaluated.
 */
@Service
class EligibilityChecker(
  private val appConfig: AppConfig,
  private val eligibilityEvaluationEngine: EligibilityEvaluationEngine,
) {

  /**
   * @throws ResponseStatusException if the offender is ineligible or evaluation fails
   * @throws EligibilityDataUnavailableException if any data provider is unavailable
   * @throws ResourceNotFoundException when a data provider fails with a 404 error
   */
  fun check(offender: Offender, name: Name, cache: EligibilityEvaluationEngine.Cache): EligibilityResult {
    val fetchCache = eligibilityEvaluationEngine.newFetchCache(cache)
    val result = try {
      eligibilityEvaluationEngine
        .evaluate(
          offender.crn,
          eligibilityEvaluationEngine.activeRuleSet,
          fetchCache,
        ).get() // we rely on the engine already having timeouts for each data provider
    } catch (_: CancellationException) {
      LOGGER.warn("Eligibility evaluation for {} cancelled", offender.crn)
      throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Eligibility evaluation cancelled")
    } catch (e: InterruptedException) {
      LOGGER.warn("Eligibility evaluation for {} interrupted", offender.crn)
      Thread.currentThread().interrupt()
      throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Eligibility evaluation interrupted", e)
    } catch (e: ExecutionException) {
      when (e.cause) {
        is EligibilityDataUnavailableException -> throw e.cause!!
        is ResourceNotFoundException -> throw e.cause!!
        else -> throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Eligibility evaluation failed: ${e.message}", e.cause)
      }
    }
    LOGGER.info("Eligibility evaluation for {} result: {}", offender.crn, result)
    return result.copy(dashboardMessage = result.dashboardMessage?.let { applyTemplate(it, name) })
  }

  companion object {
    val LOGGER = logger<EligibilityChecker>()
  }
}
