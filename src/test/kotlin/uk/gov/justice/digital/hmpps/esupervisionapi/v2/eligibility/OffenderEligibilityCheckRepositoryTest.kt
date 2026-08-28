package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class OffenderEligibilityCheckRepositoryTest : IntegrationTestBase() {

  @Autowired lateinit var offenderRepository: OffenderRepository

  @Autowired lateinit var offenderEligibilityCheckRepository: OffenderEligibilityCheckRepository

  // The native @Modifying upsert bypasses the persistence context's identity map, so a
  // findAll() re-query within the same transaction would otherwise return the stale,
  // already-loaded entity instance rather than the row's new column values.
  @PersistenceContext
  lateinit var entityManager: EntityManager

  @AfterEach
  fun cleanUp() {
    offenderEligibilityCheckRepository.deleteAll()
    offenderRepository.deleteAll()
  }

  @Test
  @Transactional
  fun `upsert inserts a new row then overwrites it on re-run instead of duplicating`() {
    val offender = offenderRepository.save(offender("V300001"))
    val ruleSet = "OFFENDER_ELIGIBILITY"
    val firstCheckedAt = Instant.parse("2026-06-01T09:00:00Z")

    offenderEligibilityCheckRepository.upsert(
      offenderId = offender.id,
      ruleSet = ruleSet,
      outcome = EligibilityCheckOutcome.ELIGIBLE.name,
      message = null,
      triggeredRuleCode = null,
      checkedAt = firstCheckedAt,
    )

    entityManager.clear()
    val afterInsert = offenderEligibilityCheckRepository.findAll()
    assertEquals(1, afterInsert.size)
    assertEquals(EligibilityCheckOutcome.ELIGIBLE, afterInsert[0].outcome)
    assertEquals(firstCheckedAt, afterInsert[0].checkedAt)

    val secondCheckedAt = Instant.parse("2026-06-02T09:00:00Z")
    offenderEligibilityCheckRepository.upsert(
      offenderId = offender.id,
      ruleSet = ruleSet,
      outcome = EligibilityCheckOutcome.INELIGIBLE.name,
      message = "Contact suspended",
      triggeredRuleCode = "IS_CONTACT_SUSPENDED",
      checkedAt = secondCheckedAt,
    )

    entityManager.clear()
    val afterUpdate = offenderEligibilityCheckRepository.findAll()
    // still one row - rerunning the sync job upserts instead of accumulating history
    assertEquals(1, afterUpdate.size)
    assertEquals(EligibilityCheckOutcome.INELIGIBLE, afterUpdate[0].outcome)
    assertEquals("Contact suspended", afterUpdate[0].message)
    assertEquals("IS_CONTACT_SUSPENDED", afterUpdate[0].triggeredRuleCode)
    assertEquals(secondCheckedAt, afterUpdate[0].checkedAt)
  }

  @Test
  @Transactional
  fun `upsert can record a DATA_UNAVAILABLE outcome`() {
    val offender = offenderRepository.save(offender("V300002"))

    offenderEligibilityCheckRepository.upsert(
      offenderId = offender.id,
      ruleSet = "OFFENDER_ELIGIBILITY",
      outcome = EligibilityCheckOutcome.DATA_UNAVAILABLE.name,
      message = "NDelius unavailable",
      triggeredRuleCode = null,
      checkedAt = Instant.now(),
    )

    val rows = offenderEligibilityCheckRepository.findAll()
    assertEquals(1, rows.size)
    assertEquals(EligibilityCheckOutcome.DATA_UNAVAILABLE, rows[0].outcome)
  }

  private fun offender(crn: String): Offender = Offender(
    uuid = UUID.randomUUID(),
    crn = crn,
    practitionerId = "PRACT1",
    status = OffenderStatus.VERIFIED,
    firstCheckin = LocalDate.of(2026, 1, 1),
    checkinInterval = Duration.ofDays(7),
    createdAt = Instant.now(),
    createdBy = "SYSTEM",
    updatedAt = Instant.now(),
    contactPreference = ContactPreference.PHONE,
  )
}
