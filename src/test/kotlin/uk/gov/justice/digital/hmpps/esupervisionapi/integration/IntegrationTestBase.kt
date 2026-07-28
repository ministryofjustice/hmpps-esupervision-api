package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.hmpps.test.kotlin.auth.JwtAuthorisationHelper

@ExtendWith(HmppsAuthApiExtension::class)
@Import(MockConfig::class)
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["spring.autoconfigure.exclude=uk.gov.justice.hmpps.sqs.HmppsSqsConfiguration"])
@ActiveProfiles("test")
abstract class IntegrationTestBase {

  // Boot 4 removed the auto-registered WebTestClient bean for @SpringBootTest(RANDOM_PORT);
  // build one bound to the live server port instead.
  @LocalServerPort
  private var port: Int = 0

  protected val webTestClient: WebTestClient by lazy {
    WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
  }

  @Autowired
  protected lateinit var jwtAuthHelper: JwtAuthorisationHelper

  internal fun setAuthorisation(
    username: String? = "AUTH_ADM",
    roles: List<String> = listOf(),
    scopes: List<String> = listOf("read,write"),
  ): (HttpHeaders) -> Unit = jwtAuthHelper.setAuthorisationHeader(username = username, scope = scopes, roles = roles)

  protected fun stubPingWithResponse(status: Int) {
    hmppsAuth.stubHealthPing(status)
  }

  companion object {
    private const val STRINGTYPE_UNSPECIFIED = "stringtype=unspecified"

    private fun withQueryParam(baseUrl: String, queryParam: String): String = if ('?' in baseUrl) "$baseUrl&$queryParam" else "$baseUrl?$queryParam"

    @DynamicPropertySource
    @JvmStatic
    fun configureProperties(registry: DynamicPropertyRegistry) {
      val postgres = TestContainersSessionListener.postgres

      registry.add("spring.datasource.url") {
        val jdbcUrl = postgres.jdbcUrl
        withQueryParam(jdbcUrl, STRINGTYPE_UNSPECIFIED)
      }
      registry.add("spring.datasource.username") { postgres.username }
      registry.add("spring.datasource.password") { postgres.password }
    }
  }
}
