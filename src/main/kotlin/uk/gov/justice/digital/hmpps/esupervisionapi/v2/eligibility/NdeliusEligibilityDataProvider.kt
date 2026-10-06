package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * NDelius-backed eligibility data. If [INdiliusApiClient.getContactDetailsStrict] returns null,
 * we treat that as a source fetch failure so the engine can surface a 404 rather than silently
 * evaluating rules against missing data. Any exception the client itself throws (e.g. a 4xx/5xx
 * not covered by its fallback) propagates through this future to the engine.
 */
@Service
class NdeliusEligibilityDataProvider(
  @Qualifier("ndeliusEligibilityApiClient") private val ndiliusApiClient: INdiliusApiClient,
  @Qualifier("eligibilityDataFetchExecutor") private val eligibilityDataFetchExecutor: Executor,
) : EligibilityDataProvider {
  override val sourceKey: String = "NDELIUS"

  override fun fetch(crn: CRN): CompletableFuture<Map<String, Any?>> = CompletableFuture.supplyAsync(
    {
      fetchData(sourceKey, crn) { crn -> ndiliusApiClient.getContactDetailsStrict(crn) }
        .eligibilityData()
    },
    eligibilityDataFetchExecutor,
  )
}

fun ContactDetails.eligibilityData(): Map<String, Any?> = mapOf(
  "ACTIVE_EVENT" to this.events.firstOrNull(),
  "CONTACT_SUSPENDED" to this.contactSuspended,
  "PRACTITIONER_ASSIGNED" to (this.practitioner != null),
)
