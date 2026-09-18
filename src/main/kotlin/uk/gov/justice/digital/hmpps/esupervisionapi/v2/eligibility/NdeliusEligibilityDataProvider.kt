package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ApiUseCase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * NDelius-backed eligibility data. If [INdiliusApiClient.getContactDetails] returns null (either
 * because the CRN wasn't found or because the client's circuit-breaker fallback was triggered),
 * we treat that as a source fetch failure so the engine can surface a 503 rather than silently
 * evaluating rules against missing data. Any exception the client itself throws (e.g. a 4xx/5xx
 * not covered by its fallback) propagates through this future to the engine.
 */
@Service
class NdeliusEligibilityDataProvider(
  private val ndiliusApiClient: INdiliusApiClient,
  @Qualifier("eligibilityDataFetchExecutor") private val eligibilityDataFetchExecutor: Executor,
) : EligibilityDataProvider {
  override val sourceKey: String = "NDELIUS"

  override fun fetch(crn: String): CompletableFuture<Map<String, Any?>> = CompletableFuture.supplyAsync(
    {
      val contactDetails = ndiliusApiClient.getContactDetailsStrict(crn, ApiUseCase.ELIGIBILITY_CHECK)
      if (contactDetails == null) {
        throw RuntimeException("Could not fetch eligibility details from NDelius for CRN: $crn")
      } else {
        contactDetails.eligibilityData()
      }
    },
    eligibilityDataFetchExecutor,
  )
}

fun ContactDetails.eligibilityData(): Map<String, Any?> = mapOf(
  "ACTIVE_EVENT" to this.events.firstOrNull(),
  "CONTACT_SUSPENDED" to this.contactSuspended,
)
