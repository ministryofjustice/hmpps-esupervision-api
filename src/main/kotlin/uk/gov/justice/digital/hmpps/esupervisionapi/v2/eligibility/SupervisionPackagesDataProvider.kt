package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.SupervisionPackageDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.SupervisionPackagesApiClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Service
class SupervisionPackagesDataProvider(
  private val supervisionPackagesApi: SupervisionPackagesApiClient,
  @Qualifier("eligibilityDataFetchExecutor") private val eligibilityDataFetchExecutor: Executor,
): EligibilityDataProvider {
  override val sourceKey: String = "SUP-PACK"

  override fun fetch(crn: CRN): CompletableFuture<Map<String, Any?>> = CompletableFuture.supplyAsync(
    {
      fetchData(sourceKey, crn)
        { crn -> supervisionPackagesApi.getSupervisionPackageDetails(crn) }
        .eligibilityData()
    },
    eligibilityDataFetchExecutor
  )
}

fun SupervisionPackageDetails.eligibilityData(): Map<String, Any?> = mapOf(
  "RECALLED" to this.isRecalled,
  "FINAL_THIRD" to this.isInFinalThird,
  "EARLY_ENGAGEMENT" to this.isInEarlyEngagement
)
