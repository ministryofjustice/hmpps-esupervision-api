package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.http.Fault
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * ESUP-2181: "could not check" must not look like "details are wrong".
 *
 * When NDelius was unreachable the identity-verify endpoint used to answer 200 with
 * "Personal details do not match our records", so a person on probation re-entering correct details
 * was rejected again. These pin the three outcomes end to end through the real NDelius client.
 */
class IdentityVerifyIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  private lateinit var checkin: OffenderCheckin

  companion object {
    private const val CRN = "X000000"
    private const val VALIDATE_PATH = "/case/$CRN/validate-details"

    private val ndelius = WireMockServer(options().dynamicPort()).apply { start() }

    @JvmStatic
    @DynamicPropertySource
    fun upstreamUrls(registry: DynamicPropertyRegistry) {
      registry.add("api.base.url.ndilius-api") { ndelius.baseUrl() }
    }

    @JvmStatic
    @AfterAll
    fun stopUpstreams() = ndelius.stop()
  }

  @BeforeEach
  fun setUp() {
    ndelius.resetAll()
    hmppsAuth.stubGrantToken()
    val offender = offenderRepository.save(offenderTemplate.copy(uuid = UUID.randomUUID(), crn = CRN).toEntity())
    checkin = checkinRepository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.CREATED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
      ),
    )
  }

  @AfterEach
  fun tearDown() {
    checkinRepository.deleteAll()
    offenderRepository.deleteAll()
  }

  @Test
  fun `verified when NDelius accepts the details`() {
    ndelius.stubFor(post(urlEqualTo(VALIDATE_PATH)).willReturn(aResponse().withStatus(200)))

    verifyIdentity()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.verified").isEqualTo(true)
      .jsonPath("$.error").doesNotExist()
  }

  @Test
  fun `a genuine 400 from NDelius still reports the details do not match`() {
    ndelius.stubFor(post(urlEqualTo(VALIDATE_PATH)).willReturn(aResponse().withStatus(400)))

    verifyIdentity()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.verified").isEqualTo(false)
      .jsonPath("$.error").isEqualTo("Personal details do not match our records")
  }

  @Test
  fun `a transport failure to NDelius is 503, not a mismatch`() {
    ndelius.stubFor(
      post(urlEqualTo(VALIDATE_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
    )

    verifyIdentity()
      .expectStatus().isEqualTo(503)
      .expectBody()
      .jsonPath("$.status").isEqualTo(503)
      .jsonPath("$.userMessage").isEqualTo("Unable to verify personal details at the moment, please try again")
      .jsonPath("$.verified").doesNotExist()

    // Pin that NDelius was actually called, so the 503 isn't from a broken auth stub.
    ndelius.verify(postRequestedFor(urlEqualTo(VALIDATE_PATH)))
  }

  private fun verifyIdentity(): WebTestClient.ResponseSpec = webTestClient.post()
    .uri("/v2/offender_checkins/{uuid}/identity-verify", checkin.uuid)
    .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(
      """{"crn":"$CRN","name":{"forename":"John","surname":"Smith"},"dateOfBirth":"1985-05-14"}""",
    )
    .exchange()
}
