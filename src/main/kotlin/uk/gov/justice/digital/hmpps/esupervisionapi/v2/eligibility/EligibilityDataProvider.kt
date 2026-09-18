package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import java.util.concurrent.CompletableFuture

/**
 * Supplies the data points for one eligibility rule `source` (e.g. "NDELIUS", "NOMIS").
 * A single source can back multiple rules/data points, so [fetch] returns all of them at once,
 * keyed by [OffenderEligibilityRule.dataPoint].
 */
interface EligibilityDataProvider {
  /** Registry key matching [OffenderEligibilityRule.source]. */
  val sourceKey: String

  /**
   * Fetches all data points this source can supply for [crn]. Must not block the calling
   * thread - implementations wrap blocking client calls via a dedicated executor.
   */
  fun fetch(crn: CRN): CompletableFuture<Map<String, Any?>>
}
