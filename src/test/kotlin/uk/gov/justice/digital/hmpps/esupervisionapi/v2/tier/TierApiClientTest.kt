package uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.time.LocalDate

class TierApiClientTest {

  private val crn = "X980484"
  private val appender = ListAppender<ILoggingEvent>()
  private val logger = LoggerFactory.getLogger(TierApiClient::class.java) as Logger

  @BeforeEach
  fun attachAppender() {
    appender.start()
    logger.addAppender(appender)
  }

  @AfterEach
  fun detachAppender() {
    logger.detachAppender(appender)
    appender.stop()
  }

  private fun clientReturning(response: ClientResponse) = TierApiClient(
    WebClient.builder().exchangeFunction { Mono.just(response) }.build(),
  )

  private fun loggedMessages() = appender.list.map { it.formattedMessage }

  @Test
  fun `records the upstream challenge so an expired token can be told from a missing one`() {
    val challenge = """Bearer error="invalid_token", error_description="Jwt expired at 2026-09-07T16:39:00Z""""
    val response = ClientResponse.create(HttpStatus.UNAUTHORIZED)
      .header(HttpHeaders.WWW_AUTHENTICATE, challenge)
      .build()

    val thrown = assertThrows<ResponseStatusException> { clientReturning(response).getTierDetails(crn, TierApiVersion.V2) }

    assertEquals(HttpStatus.UNAUTHORIZED, thrown.statusCode)
    val logged = loggedMessages().single { it.startsWith("Error fetching tier details") }
    assertTrue(logged.contains("wwwAuthenticate=$challenge"), "expected the challenge in: $logged")
  }

  @Test
  fun `says so explicitly when the upstream offers no explanation at all`() {
    val response = ClientResponse.create(HttpStatus.UNAUTHORIZED).build()

    assertThrows<ResponseStatusException> { clientReturning(response).getTierDetails(crn, TierApiVersion.V2) }

    val logged = loggedMessages().single { it.startsWith("Error fetching tier details") }
    assertTrue(logged.contains("(no challenge or body)"), "expected the empty marker in: $logged")
  }

  @Test
  fun `sanitises PII out of a logged error body but keeps the CRN`() {
    val response = ClientResponse.create(HttpStatus.BAD_REQUEST)
      .body("""{"crn":"$crn","forename":"John","surname":"Doe","message":"bad request"}""")
      .build()

    assertThrows<ResponseStatusException> { clientReturning(response).getTierDetails(crn, TierApiVersion.V2) }

    val logged = loggedMessages().single { it.startsWith("Error fetching tier details") }
    assertFalse(logged.contains("John"), "forename leaked into: $logged")
    assertFalse(logged.contains("Doe"), "surname leaked into: $logged")
    assertTrue(logged.contains(crn), "expected the CRN in: $logged")
  }

  @Test
  fun `sanitises before truncating so a PII field split by the cut cannot leak`() {
    // Position the forename value so the 500-character logging cut falls inside it. Truncating
    // first would leave `"forename":"John` behind, which PiiSanitizer's `"[^"]*"` no longer
    // matches, and the partial name would reach the log.
    val padding = "x".repeat(470)
    val response = ClientResponse.create(HttpStatus.BAD_REQUEST)
      .body("""{"padding":"$padding","forename":"Johnathan","surname":"Doe"}""")
      .build()

    assertThrows<ResponseStatusException> { clientReturning(response).getTierDetails(crn, TierApiVersion.V2) }

    val logged = loggedMessages().single { it.startsWith("Error fetching tier details") }
    assertFalse(logged.contains("John"), "a split forename leaked into: $logged")
  }

  @Test
  fun `a 404 stays a not-found and is not dressed up as an auth problem`() {
    val response = ClientResponse.create(HttpStatus.NOT_FOUND).build()

    val thrown = assertThrows<ResponseStatusException> { clientReturning(response).getTierDetails(crn, TierApiVersion.V2) }

    assertEquals(HttpStatus.NOT_FOUND, thrown.statusCode)
  }

  @Test
  fun `a 5xx degrades to service unavailable`() {
    val response = ClientResponse.create(HttpStatus.BAD_GATEWAY).build()

    val thrown = assertThrows<ResponseStatusException> { clientReturning(response).getTierDetails(crn, TierApiVersion.V2) }

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.statusCode)
  }

  @Test
  fun `asks the version's path and reads the v3 shape, including its date-time and provisional flag`() {
    // Verbatim shape of hmpps-tier's TierV3Dto: calculationDate is a LocalDateTime upstream.
    val body = """{"tierScore":"D","calculationId":"11111111-2222-3333-4444-555555555555","calculationDate":"2026-10-01T08:15:30.123","changeReason":null,"provisional":true}"""
    val requested = mutableListOf<String>()
    val client = TierApiClient(
      WebClient.builder().exchangeFunction { request ->
        requested += request.url().path
        Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, "application/json").body(body).build())
      }.build(),
    )

    val details = client.getTierDetails(crn, TierApiVersion.V3)!!
    client.getTierDetails(crn, TierApiVersion.V2)

    assertEquals(listOf("/v3/crn/$crn/tier", "/v2/crn/$crn/tier"), requested)
    assertEquals("D", details.tierScore)
    assertEquals(LocalDate.of(2026, 10, 1), details.calculationDate)
    assertEquals(true, details.provisional)
  }
}
