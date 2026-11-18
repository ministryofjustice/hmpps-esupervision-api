package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Instant
import java.util.UUID

class PilotPractitionerLookupIT : IntegrationTestBase() {

  @Autowired private lateinit var offenderRepository: OffenderRepository

  @Autowired private lateinit var offenderSetupRepository: OffenderSetupRepository

  @Autowired private lateinit var lookup: PilotPractitionerLookup

  private val offenderIds = mutableListOf<Long>()

  @AfterEach
  fun cleanUp() {
    offenderSetupRepository.deleteAll(offenderSetupRepository.findAll().filter { it.offender.id in offenderIds })
    offenderRepository.deleteAllById(offenderIds)
    offenderIds.clear()
  }

  @Test
  fun `a practitioner allocated a pilot case has had one`() {
    saveOffender("X100001", practitionerId = "ALLOCATED.PRACT", inPilot = true)

    assertThat(lookup.hasHadPilotCase(" ALLOCATED.PRACT ")).isTrue()
    assertThat(lookup.allPilotPractitionerIds()).contains("ALLOCATED.PRACT")
  }

  @Test
  fun `a practitioner who set up a pilot case has had one after it moves to someone else`() {
    val offender = saveOffender("X100002", practitionerId = "NEW.PRACT", inPilot = true)
    offenderSetupRepository.saveAndFlush(
      OffenderSetup(uuid = UUID.randomUUID(), offender = offender, practitionerId = "ORIGINAL.PRACT", createdAt = Instant.now()),
    )

    assertThat(lookup.hasHadPilotCase("ORIGINAL.PRACT")).isTrue()
    assertThat(lookup.allPilotPractitionerIds()).contains("ORIGINAL.PRACT", "NEW.PRACT")
  }

  @Test
  fun `cases set up after the pilot cut-off do not count`() {
    val offender = saveOffender("X100003", practitionerId = "LATER.PRACT", inPilot = false)
    offenderSetupRepository.saveAndFlush(
      OffenderSetup(uuid = UUID.randomUUID(), offender = offender, practitionerId = "LATER.PRACT", createdAt = Instant.now()),
    )

    assertThat(lookup.hasHadPilotCase("LATER.PRACT")).isFalse()
    assertThat(lookup.allPilotPractitionerIds()).doesNotContain("LATER.PRACT")
  }

  @Test
  fun `a pilot setup that never completed does not count`() {
    val offender = saveOffender("X100005", practitionerId = "ABANDONED.PRACT", inPilot = true, status = OffenderStatus.INITIAL)
    offenderSetupRepository.saveAndFlush(
      OffenderSetup(uuid = UUID.randomUUID(), offender = offender, practitionerId = "ABANDONED.PRACT", createdAt = Instant.now()),
    )

    assertThat(lookup.hasHadPilotCase("ABANDONED.PRACT")).isFalse()
    assertThat(lookup.allPilotPractitionerIds()).doesNotContain("ABANDONED.PRACT")
  }

  @Test
  fun `no practitioner has had no pilot case`() {
    saveOffender("X100004", practitionerId = "ALLOCATED.PRACT", inPilot = true)

    assertThat(lookup.hasHadPilotCase(null)).isFalse()
    assertThat(lookup.hasHadPilotCase(" ")).isFalse()
    assertThat(PilotPractitionerLookup.isPilotPractitioner(setOf("ALLOCATED.PRACT"), "ALLOCATED.PRACT")).isTrue()
    assertThat(PilotPractitionerLookup.isPilotPractitioner(setOf("ALLOCATED.PRACT"), null)).isFalse()
  }

  private fun saveOffender(crn: String, practitionerId: String, inPilot: Boolean, status: OffenderStatus = OffenderStatus.VERIFIED): Offender {
    val offender = offenderTemplate.copy(uuid = UUID.randomUUID(), crn = crn, practitionerId = practitionerId, status = status).toEntity()
      .also { it.inPilot = inPilot }
    return offenderRepository.saveAndFlush(offender).also { offenderIds += it.id }
  }
}
