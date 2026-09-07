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

    val thrown = assertThrows<ResponseStatusException> { clientReturning(response).getTierDetails(crn) }

    assertEquals(HttpStatus.UNAUTHORIZED, thrown.statusCode)
    val logged = loggedMessages().single { it.startsWith("Error fetching tier details") }
    assertTrue(logged.contains("wwwAuthenticate=$challenge"), "expected the challenge in: $logged")
  }

  @Test
  fun `says so explicitly when the upstream offers no explanation at all`() {
    val response = ClientResponse.create(HttpStatus.UNAUTHORIZED).build()

    assertThrows<ResponseStatusException> { clientReturning(response).getTierDetails(crn) }

    val logged = loggedMessages().single { it.startsWith("Error fetching tier details") }
    assertTrue(logged.contains("(no challenge or body)"), "expected the empty marker in: $logged")
  }

  @Test
  fun `sanitises PII out of a logged error body but keeps the CRN`() {
    val response = ClientResponse.create(HttpStatus.BAD_REQUEST)
      .body("""{"crn":"$crn","forename":"John","surname":"Doe","message":"bad request"}""")
      .build()

    assertThrows<ResponseStatusException> { clientReturning(response).getTierDetails(crn) }

    val logged = loggedMessages().single { it.startsWith("Error fetching tier details") }
    assertFalse(logged.contains("John"), "forename leaked into: $logged")
    assertFalse(logged.contains("Doe"), "surname leaked into: $logged")
    assertTrue(logged.contains(crn), "expected the CRN in: $logged")
  }

  @Test
  fun `a 404 stays a not-found and is not dressed up as an auth problem`() {
    val response = ClientResponse.create(HttpStatus.NOT_FOUND).build()

    val thrown = assertThrows<ResponseStatusException> { clientReturning(response).getTierDetails(crn) }

    assertEquals(HttpStatus.NOT_FOUND, thrown.statusCode)
  }

  @Test
  fun `a 5xx degrades to service unavailable`() {
    val response = ClientResponse.create(HttpStatus.BAD_GATEWAY).build()

    val thrown = assertThrows<ResponseStatusException> { clientReturning(response).getTierDetails(crn) }

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.statusCode)
  }
}
