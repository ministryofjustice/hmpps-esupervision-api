package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.ResourceNotFoundException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.jvm.optionals.getOrElse

@Service
class EsupervisionEligibilityDataProvider(
  private val offenderRepository: OffenderRepository,
  @Qualifier("eligibilityDataFetchExecutor") private val eligibilityDataFetchExecutor: Executor,
): EligibilityDataProvider {
  override val sourceKey: String
    get() = "ESUP"

  override fun fetch(crn: CRN): CompletableFuture<Map<String, Any?>> = CompletableFuture.supplyAsync(
    {
      offenderRepository.findByCrn(crn).getOrElse {
        throw ResourceNotFoundException("Could not fetch eligibility details from $sourceKey for CRN: $crn")
      }.eligibilityData()
    },
    eligibilityDataFetchExecutor)
}

fun Offender.eligibilityData(): Map<String, Any?> = mapOf(
  "PILOT_USER" to this.inPilot
)