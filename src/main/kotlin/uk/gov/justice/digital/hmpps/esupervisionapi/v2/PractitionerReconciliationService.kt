package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.allocatedPractitionerUsername
import java.time.Clock
import java.time.Instant

@Service
class PractitionerReconciliationService(
  private val offenderRepository: OffenderRepository,
  private val transactionTemplate: TransactionTemplate,
  private val clock: Clock,
  private val entityManager: EntityManager,
) {
  fun reconcile(offender: Offender, details: ContactDetails?) {
    val username = details?.allocatedPractitionerUsername() ?: return
    require(details.crn == offender.crn) { "Contact details do not match offender CRN" }
    if (username.equals(offender.practitionerId, ignoreCase = true)) return

    val allocation = updateAllocation(offender.id, details.crn, username)
    offender.practitionerId = allocation.first
    offender.updatedAt = allocation.second
  }

  fun reconcile(offenderId: Long, currentPractitionerId: String, details: ContactDetails): String {
    val username = details.allocatedPractitionerUsername() ?: return currentPractitionerId
    if (username.equals(currentPractitionerId, ignoreCase = true)) return currentPractitionerId
    return updateAllocation(offenderId, details.crn, username).first
  }

  private fun updateAllocation(offenderId: Long, crn: String, username: String): Pair<String, Instant> = transactionTemplate.execute {
    val offender = offenderRepository.findByIdForUpdate(offenderId).orElseThrow()
    // The request's persistence context may have cached this offender before another update.
    entityManager.refresh(offender)
    require(offender.crn == crn) { "Contact details do not match offender CRN" }
    if (!username.equals(offender.practitionerId, ignoreCase = true)) {
      offender.practitionerId = username
      offender.updatedAt = clock.instant()
    }
    offender.practitionerId to offender.updatedAt
  } ?: error("Could not reconcile practitioner for offender $offenderId")
}
