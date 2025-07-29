package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerDto

class PractitionerSetupTest : IntegrationTestBase() {

  private lateinit var practitionerCreatorRoleAuthHeaders: (HttpHeaders) -> Unit

  @BeforeEach
  internal fun setUp() {
    practitionerCreatorRoleAuthHeaders = setAuthorisation(roles = listOf("ESUPERVISION__ESUPERVISION_UI"))
  }

  @Test
  fun `create a a practitioner`() {
    val newPractitioner = PractitionerDto(
      java.util.UUID.randomUUID().toString(),
      "Larry",
      "Practitioner",
      "larry@exmaple.com",
      null,
      roles = listOf(),
    )

    val createResult = createPractitionerRequest(newPractitioner)
      .exchange()
      .expectStatus().isCreated
      .expectBody(Unit::class.java)
      .returnResult()

    val location = createResult.responseHeaders.getFirst(HttpHeaders.LOCATION)
    Assertions.assertThat(location).isNotNull

    val fetchResult = webTestClient.get()
      .uri(location!!)
      .headers(practitionerCreatorRoleAuthHeaders)
      .exchange()
      .expectStatus().isOk
      .expectBody(PractitionerDto::class.java)
      .returnResult()

    Assertions.assertThat(fetchResult.responseBody).isNotNull

    createPractitionerRequest(newPractitioner)
      .exchange().expectStatus().is4xxClientError
  }

  private fun createPractitionerRequest(newPractitioner: PractitionerDto): WebTestClient.RequestHeadersSpec<*> = webTestClient.post()
    .uri("/practitioners")
    .contentType(MediaType.APPLICATION_JSON)
    .headers(practitionerCreatorRoleAuthHeaders)
    .bodyValue(newPractitioner)
}
