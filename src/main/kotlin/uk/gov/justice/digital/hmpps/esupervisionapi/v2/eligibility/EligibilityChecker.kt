package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.checkinIneligibilityReason

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
   * @throws ResponseStatusException if the offender is ineligible to reactivate
   */
  fun check(offender: Offender, contactDetails: ContactDetails) {
    var ineligibilityMessage: String? = null
    if (appConfig.enabledFeatures.contains(Feature.ESUP_2082)) {
      val result = eligibilityEvaluationEngine.evaluate(offender.crn, eligibilityEvaluationEngine.activeRuleSet).get()
      if (result.outcome == EligibilityCheckOutcome.INELIGIBLE) {
        ineligibilityMessage = result.message
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
        "Cannot reactivate ${offender.crn}: $ineligibilityMessage",
      )
    }
  }
}
