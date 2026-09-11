package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

/**
 * Stub only - no real Nomis client exists in this codebase yet. Returns a hardcoded
 * placeholder so the "recalled to prison" rule has a working, swappable data seam.
 * TODO: replace with a real NomisApiClient-backed provider once Nomis integration exists
 *  (see [uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.ArnsApiClient]/
 *  [uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.TierApiClient] for the client pattern to follow).
 */
@Service
class NomisEligibilityDataProvider : EligibilityDataProvider {
  override val sourceKey: String = "NOMIS"

  override fun fetch(crn: String): CompletableFuture<Map<String, Any?>> = CompletableFuture.completedFuture(mapOf("RECALL_STATUS" to null))
}
