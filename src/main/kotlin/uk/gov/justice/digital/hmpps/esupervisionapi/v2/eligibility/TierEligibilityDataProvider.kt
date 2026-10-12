package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.ITierApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.TierApiVersion
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.TierDetails
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Service
class TierEligibilityDataProvider(
  @Qualifier("tierEligibilityApiClient") private val tierApiClient: ITierApiClient,
  @Qualifier("eligibilityDataFetchExecutor") private val eligibilityDataFetchExecutor: Executor,
) : EligibilityDataProvider {
  override val sourceKey: String
    get() = "TIER"

  override fun fetch(crn: CRN): CompletableFuture<Map<String, Any?>> = CompletableFuture.supplyAsync(
    {
      val details = tierApiClient.getTierDetails(crn, TierApiVersion.V3)
      if (details == null) {
        throw IllegalStateException("Tier details are null for $crn")
      }
      details.eligibilityData()
    },
    eligibilityDataFetchExecutor,
  )
}

fun TierDetails.eligibilityData(): Map<String, Any?> = mapOf(
  "PROVISIONAL" to this.provisional,
  "TIER" to this.tierScore,
)
