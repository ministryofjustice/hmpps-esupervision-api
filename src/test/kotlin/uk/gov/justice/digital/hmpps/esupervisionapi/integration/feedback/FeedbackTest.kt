package uk.gov.justice.digital.hmpps.esupervisionapi.integration.feedback

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType.APPLICATION_JSON
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase

class FeedbackTest : IntegrationTestBase() {

  private lateinit var practitionerRoleAuthHeaders: (HttpHeaders) -> Unit

  @BeforeEach
  internal fun setUp() {
    practitionerRoleAuthHeaders = setAuthorisation(roles = listOf("ESUPERVISION__ESUPERVISION_UI"))
  }

  @Test
  fun `Feedback endpoints support creation and getting of feedback`() {
    webTestClient.get()
      .uri("/v2/feedback")
      .headers(practitionerRoleAuthHeaders)
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content.length()").isEqualTo(0)

    webTestClient.post()
      .uri("/v2/feedback")
      .headers(practitionerRoleAuthHeaders)
      .contentType(APPLICATION_JSON)
      .bodyValue(
        """
        {
          "feedback": {
            "version": 1,
            "howEasy": "veryEasy",
            "gettingSupport": "yes",
            "improvements": [
              "findingOutAboutCheckIns",
              "textOrEmailNotifications",
              "somethingElse"
            ]
          }
        }
        """.trimIndent(),
      )
      .exchange()
      .expectStatus().isCreated

    webTestClient.get()
      .uri("/v2/feedback")
      .headers(practitionerRoleAuthHeaders)
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content.length()").isEqualTo(1)
      .jsonPath("$.content[0].feedback.howEasy").isEqualTo("veryEasy")
      .jsonPath("$.content[0].feedback.gettingSupport").isEqualTo("yes")
      .jsonPath("$.content[0].feedback.improvements[0]").isEqualTo("findingOutAboutCheckIns")
      .jsonPath("$.content[0].feedback.improvements[1]").isEqualTo("textOrEmailNotifications")
      .jsonPath("$.content[0].feedback.improvements[2]").isEqualTo("somethingElse")
  }

  @Test
  fun `Create feedback endpoint gives 400 if no version is provided in payload`() {
    webTestClient.post()
      .uri("/v2/feedback")
      .headers(practitionerRoleAuthHeaders)
      .contentType(APPLICATION_JSON)
      .bodyValue(
        """
        {
          "feedback": {
            "howEasy": "veryEasy",
            "gettingSupport": "yes",
            "improvements": [
              "findingOutAboutCheckIns",
              "textOrEmailNotifications",
              "somethingElse"
            ]
          }
        }
        """.trimIndent(),
      )
      .exchange()
      .expectStatus().isBadRequest
  }
}
