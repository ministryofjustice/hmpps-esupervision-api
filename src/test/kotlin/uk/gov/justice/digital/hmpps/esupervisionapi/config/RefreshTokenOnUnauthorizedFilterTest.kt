package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono
import uk.gov.justice.hmpps.kotlin.auth.service.GlobalPrincipalOAuth2AuthorizedClientService.Companion.GLOBAL_SYSTEM_PRINCIPAL
import java.net.URI

class RefreshTokenOnUnauthorizedFilterTest {

  private val authorizedClientService: OAuth2AuthorizedClientService = mock()
  private val filter = RefreshTokenOnUnauthorizedFilter("tier-api", authorizedClientService)

  private fun request(method: HttpMethod = HttpMethod.GET) = ClientRequest.create(method, URI.create("http://tier.local/v2/crn/X980484/tier")).build()

  /** Hands back [statuses] in order, one per exchange, and records how many were asked for. */
  private class StubExchange(private vararg val statuses: HttpStatus) : ExchangeFunction {
    var exchanges = 0
      private set

    override fun exchange(request: ClientRequest): Mono<ClientResponse> = Mono.fromSupplier {
      ClientResponse.create(statuses[exchanges++]).build()
    }
  }

  @Test
  fun `retries once with a fresh token when the upstream rejects the token`() {
    val exchange = StubExchange(HttpStatus.UNAUTHORIZED, HttpStatus.OK)

    val response = filter.filter(request(), exchange).block()!!

    assertEquals(HttpStatus.OK, response.statusCode())
    assertEquals(2, exchange.exchanges)
    verify(authorizedClientService).removeAuthorizedClient("tier-api", GLOBAL_SYSTEM_PRINCIPAL)
  }

  @Test
  fun `leaves a successful exchange alone`() {
    val exchange = StubExchange(HttpStatus.OK)

    val response = filter.filter(request(), exchange).block()!!

    assertEquals(HttpStatus.OK, response.statusCode())
    assertEquals(1, exchange.exchanges)
    verify(authorizedClientService, never()).removeAuthorizedClient(any(), any())
  }

  @Test
  fun `gives up after a single retry so a genuinely unauthorised client cannot loop`() {
    val exchange = StubExchange(HttpStatus.UNAUTHORIZED, HttpStatus.UNAUTHORIZED)

    val response = filter.filter(request(), exchange).block()!!

    assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode())
    assertEquals(2, exchange.exchanges)
    verify(authorizedClientService).removeAuthorizedClient("tier-api", GLOBAL_SYSTEM_PRINCIPAL)
  }

  @Test
  fun `does not replay a request that carries a body`() {
    val exchange = StubExchange(HttpStatus.UNAUTHORIZED)

    val response = filter.filter(request(HttpMethod.POST), exchange).block()!!

    assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode())
    assertEquals(1, exchange.exchanges)
    verify(authorizedClientService, never()).removeAuthorizedClient(any(), any())
  }

  @Test
  fun `does not retry other client errors`() {
    val exchange = StubExchange(HttpStatus.FORBIDDEN)

    val response = filter.filter(request(), exchange).block()!!

    assertEquals(HttpStatus.FORBIDDEN, response.statusCode())
    assertEquals(1, exchange.exchanges)
    verify(authorizedClientService, never()).removeAuthorizedClient(any(), any())
  }
}
