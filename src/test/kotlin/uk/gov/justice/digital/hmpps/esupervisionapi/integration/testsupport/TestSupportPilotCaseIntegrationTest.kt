package uk.gov.justice.digital.hmpps.esupervisionapi.integration.testsupport

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.PilotPractitionerLookup
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.testsupport.OffenderCleanupRepository
import java.util.UUID

@TestPropertySource(properties = ["app.test-support.enabled=true", "app.env=DEV"])
class TestSupportPilotCaseIntegrationTest : IntegrationTestBase() {

  @Autowired lateinit var offenderRepository: OffenderRepository

  @Autowired lateinit var cleanupRepository: OffenderCleanupRepository

  @Autowired lateinit var pilotPractitionerLookup: PilotPractitionerLookup

  private val pilotCase = "T200001"
  private val bystander = "T200002"

  @AfterEach
  fun cleanUp() {
    cleanupRepository.deleteByCrn(pilotCase)
    cleanupRepository.deleteByCrn(bystander)
  }

  private fun seed(crn: String) = offenderRepository.save(offenderTemplate.copy(crn = crn, uuid = UUID.randomUUID()).toEntity())

  private fun inPilot(crn: String) = offenderRepository.findByCrn(crn).get().inPilot

  private fun setPilot(crn: String, inPilot: Boolean, roles: List<String> = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")) = webTestClient.put()
    .uri("/v2/test-support/offenders/crn/$crn/pilot")
    .headers(setAuthorisation(roles = roles))
    .bodyValue(mapOf("inPilot" to inPilot))
    .exchange()

  @Test
  fun `marks a person as a pilot case, and back again, without touching anyone else`() {
    val person = seed(pilotCase)
    seed(bystander)

    setPilot(pilotCase, true).expectStatus().isOk
      .expectBody()
      .jsonPath("$.crn").isEqualTo(pilotCase)
      .jsonPath("$.inPilot").isEqualTo(true)
      .jsonPath("$.practitionerId").isEqualTo(person.practitionerId)
      .jsonPath("$.status").isEqualTo(person.status.name)
    assertThat(inPilot(pilotCase)).isTrue()
    assertThat(inPilot(bystander)).isFalse()

    setPilot(pilotCase, false).expectStatus().isOk
      .expectBody().jsonPath("$.inPilot").isEqualTo(false)
    assertThat(inPilot(pilotCase)).isFalse()
  }

  @Test
  fun `makes the person's practitioner a pilot practitioner for the eligibility rules, and back again`() {
    val person = seed(pilotCase)
    assertThat(pilotPractitionerLookup.hasHadPilotCase(person.practitionerId)).isFalse()

    setPilot(pilotCase, true).expectStatus().isOk
    assertThat(pilotPractitionerLookup.hasHadPilotCase(person.practitionerId)).isTrue()

    setPilot(pilotCase, false).expectStatus().isOk
    assertThat(pilotPractitionerLookup.hasHadPilotCase(person.practitionerId)).isFalse()
  }

  @Test
  fun `takes the CRN in any case`() {
    seed(pilotCase)

    setPilot(pilotCase.lowercase(), true).expectStatus().isOk
    assertThat(inPilot(pilotCase)).isTrue()
  }

  @Test
  fun `an unknown CRN is not found`() {
    setPilot("T299999", true).expectStatus().isNotFound
  }

  @Test
  fun `something that is not a CRN is rejected`() {
    setPilot("not-a-crn", true).expectStatus().isBadRequest
  }

  @Test
  fun `requires the role`() {
    seed(pilotCase)

    setPilot(pilotCase, true, roles = listOf("ROLE_SOMETHING_ELSE")).expectStatus().isForbidden
    assertThat(inPilot(pilotCase)).isFalse()
  }
}
