package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import java.time.LocalDate

/**
 * scripts/run_pop_mailing_list_export.sh and run_pop_contact_export.sh tell an API that predates
 * `dateOfDeath` from a person who has not died by whether the key is there at all. That holds only
 * while the app's mapper writes null fields, so this pins it.
 */
class ContactDetailsJsonContractTest : IntegrationTestBase() {

  @Autowired
  private lateinit var objectMapper: ObjectMapper

  @Test
  fun `dateOfDeath is written as null, not left out, for someone who has not died`() {
    val details = ContactDetails(crn = "X123456", name = Name("John", "Doe"), dateOfBirth = LocalDate.of(1980, 1, 1))

    val json = objectMapper.readTree(objectMapper.writeValueAsString(details))

    assertThat(json.has("dateOfDeath")).isTrue()
    assertThat(json.get("dateOfDeath").isNull).isTrue()
  }

  @Test
  fun `dateOfDeath is written as an ISO date`() {
    val details = ContactDetails(
      crn = "X123456",
      name = Name("John", "Doe"),
      dateOfBirth = LocalDate.of(1980, 1, 1),
      dateOfDeath = LocalDate.of(2026, 3, 4),
    )

    assertThat(objectMapper.readTree(objectMapper.writeValueAsString(details)).get("dateOfDeath").asString()).isEqualTo("2026-03-04")
  }
}
