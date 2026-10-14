package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import java.util.concurrent.CompletableFuture

/**
 * This provider should never be called and data that any eligibility rule specifies as MANUAL.
 * It's only job is to signal an error to the caller.
 *
 * should be injected into the FetchCache before calling any of the relevant [EligibilityEvaluationEngine] methods.
 */
@Service
class ManualEligibilityDataProvider : EligibilityDataProvider {
  override val sourceKey: String
    get() = "MANUAL"

  override fun fetch(crn: CRN): CompletableFuture<Map<String, Any?>> = throw EligibilityDataUnavailableException("*", sourceKey, RuntimeException("Missing eligibility data for $crn"))
}
