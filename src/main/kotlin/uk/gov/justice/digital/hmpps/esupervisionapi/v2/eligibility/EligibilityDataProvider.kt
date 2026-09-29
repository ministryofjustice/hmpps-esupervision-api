package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.ResourceNotFoundException
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
   *
   * Implementations should use [uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.ResourceNotFoundException] to
   * signal that the source does not have data for given [crn].
   */
  fun fetch(crn: CRN): CompletableFuture<Map<String, Any?>>
}

/**
 * Utility function for fetching data and surfacing null response as a 404
 *
 * @throws ResourceNotFoundException if [fetcher] returns null
 */
fun <T> fetchData(sourceKey: String, crn: CRN, fetcher: (crn: CRN) -> T?): T {
  val data = fetcher(crn)
  if (data == null) {
    throw ResourceNotFoundException("Could not fetch eligibility details from $sourceKey for CRN: $crn")
  }
  return data
}
