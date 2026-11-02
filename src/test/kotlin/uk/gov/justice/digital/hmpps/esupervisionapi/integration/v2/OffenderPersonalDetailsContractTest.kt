package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.module.kotlin.jacksonObjectMapper
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import java.time.LocalDate

/**
 * scripts/run_pop_mailing_list_export.sh and run_pop_contact_export.sh read `details.dateOfDeath`
 * from this endpoint, and tell an API that predates it from a person who has not died by whether
 * the key is there at all. So it must be written, as null, for someone who has not died.
 */
class OffenderPersonalDetailsContractTest : IntegrationTestBase() {

  private val crn = "D123456"
  private val mapper = jacksonObjectMapper()

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @MockitoBean
  private lateinit var ndiliusApiClient: INdiliusApiClient

  @AfterEach
  fun tearDown() {
    offenderRepository.deleteAll()
  }

  private fun fetchDetails(dateOfDeath: LocalDate?) = run {
    offenderRepository.save(offenderTemplate.copy(crn = crn).toEntity())
    whenever(ndiliusApiClient.getContactDetails(crn)).thenReturn(
      ContactDetails(crn = crn, name = Name("John", "Doe"), dateOfBirth = LocalDate.of(1980, 1, 1), dateOfDeath = dateOfDeath),
    )
    webTestClient.get()
      .uri("/v2/offenders/crn/$crn?include-personal-details=true")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
      .exchange()
      .expectStatus().isOk
      .expectBody(String::class.java).returnResult().responseBody!!
      .let { mapper.readTree(it).get("details") }
  }

  @Test
  fun `dateOfDeath is written as null, not left out, for someone who has not died`() {
    val details = fetchDetails(dateOfDeath = null)

    // jsonPath's exists() can't tell a null from a missing key, so look at the tree
    assertThat(details.has("dateOfDeath")).isTrue()
    assertThat(details.get("dateOfDeath").isNull).isTrue()
  }

  @Test
  fun `dateOfDeath is written as an ISO date`() {
    assertThat(fetchDetails(dateOfDeath = LocalDate.of(2026, 3, 4)).get("dateOfDeath").asString()).isEqualTo("2026-03-04")
  }
}
