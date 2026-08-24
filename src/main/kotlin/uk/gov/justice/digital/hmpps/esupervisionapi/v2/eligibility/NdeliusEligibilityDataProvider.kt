package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

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
  private val eligibilityDataFetchExecutor: ExecutorService,
) : EligibilityDataProvider {
  override val sourceKey: String = "NDELIUS"

  override fun fetch(crn: String): CompletableFuture<Map<String, Any?>> = CompletableFuture.supplyAsync(
    {
      val contactDetails = ndiliusApiClient.getContactDetails(crn)
      if (contactDetails == null) {
        throw RuntimeException("Could not fetch contact details from NDelius for CRN: $crn")
      } else {
        mapOf(
          // "DECEASED_DATE" to contactDetails?.deceasedDate,
          "ACTIVE_EVENT" to contactDetails.events.firstOrNull(),
          "CONTACT_SUSPENDED" to contactDetails.contactSuspended,
        )
      }
    },
    eligibilityDataFetchExecutor,
  )
}

fun ContactDetails.eligibilityData(): Map<String, Any?> = mapOf(
  "ACTIVE_EVENT" to this.events.firstOrNull(),
  "CONTACT_SUSPENDED" to this.contactSuspended,
)
