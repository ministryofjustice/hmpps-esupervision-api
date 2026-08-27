package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.checkinIneligibilityReason
import java.util.concurrent.ExecutionException

/**
 * A shim around the [EligibilityEvaluationEngine] meant to hide the differences between the
 * pilot eligibility code paths and the rule-based eligibility code paths in HTTP resources.
 */
@Service
class EligibilityChecker(
  private val appConfig: AppConfig,
  private val eligibilityEvaluationEngine: EligibilityEvaluationEngine,
) {
  /**
   * @throws ResponseStatusException if the offender is ineligible or evaluation fails
   * @throws EligibilityDataUnavailableException if any data provider is unavailable
   */
  fun check(offender: Offender, contactDetails: ContactDetails) {
    var ineligibilityMessage: String? = null
    if (appConfig.enabledFeatures.contains(Feature.ESUP_2082)) {
      val result = try {
        eligibilityEvaluationEngine
          .evaluate(
            offender.crn,
            eligibilityEvaluationEngine.activeRuleSet,
            mapOf(
              "NDELIUS" to java.util.concurrent.CompletableFuture.completedFuture(contactDetails.eligibilityData()),
            ),
          ).get() // we rely on the engine already having timeouts for each data provider
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Eligibility evaluation interrupted")
      } catch (e: ExecutionException) {
        throw (e.cause as? EligibilityDataUnavailableException)
          ?: ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Eligibility evaluation failed: ${e.message}", e.cause)
      }
      LOGGER.info("Eligibility evaluation for {} result: {}", offender.crn, result)
      if (result.outcome == EligibilityCheckOutcome.INELIGIBLE) {
        ineligibilityMessage = result.message ?: "Eligibility rule ${result.triggeredRuleCode ?: "UNKNOWN"} failed"
      }
    } else {
      val ineligibility = checkinIneligibilityReason(offender, contactDetails)
      if (ineligibility != null) {
        ineligibilityMessage = ineligibility.description
      }
    }
    if (ineligibilityMessage != null) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Offender ${offender.crn} not eligible: $ineligibilityMessage",
      )
    }
  }

  companion object {
    val LOGGER = logger<EligibilityChecker>()
  }
}
