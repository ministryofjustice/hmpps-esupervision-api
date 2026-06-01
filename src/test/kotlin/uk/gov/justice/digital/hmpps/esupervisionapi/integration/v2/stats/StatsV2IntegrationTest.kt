package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2.stats

import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase

class StatsV2IntegrationTest : IntegrationTestBase() {

  @Test
  fun `get stats returns 200 with empty stats when no data exists for range`() {
    webTestClient.get()
      .uri("/v2/stats?fromMonth=2020-01&toMonth=2020-02")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.total.totalSignedUp").isEqualTo(0)
      .jsonPath("$.total.feedbackTotal").isEqualTo(0)
  }
}
