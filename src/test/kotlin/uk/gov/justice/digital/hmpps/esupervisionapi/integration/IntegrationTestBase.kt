package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderEventLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerService
import uk.gov.justice.hmpps.test.kotlin.auth.JwtAuthorisationHelper

@ExtendWith(HmppsAuthApiExtension::class)
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

  @Autowired
  protected lateinit var webTestClient: WebTestClient

  @Autowired
  protected lateinit var jwtAuthHelper: JwtAuthorisationHelper

  @Autowired protected lateinit var practitionerService: PractitionerService

  @Autowired protected lateinit var offenderSetupRepository: OffenderSetupRepository

  @Autowired protected lateinit var offenderRepository: OffenderRepository

  @Autowired protected lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired protected lateinit var offenderEventLogRepository: OffenderEventLogRepository

  internal fun setAuthorisation(
    username: String? = "AUTH_ADM",
    roles: List<String> = listOf(),
    scopes: List<String> = listOf("read,write"),
  ): (HttpHeaders) -> Unit = jwtAuthHelper.setAuthorisationHeader(username = username, scope = scopes, roles = roles)

  protected fun stubPingWithResponse(status: Int) {
    hmppsAuth.stubHealthPing(status)
  }
}
