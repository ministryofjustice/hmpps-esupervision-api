package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono
import uk.gov.justice.hmpps.kotlin.auth.service.GlobalPrincipalOAuth2AuthorizedClientService.Companion.GLOBAL_SYSTEM_PRINCIPAL

/**
 * Evicts the cached client-credentials token and retries once when an upstream answers 401.
 *
 * `ServletOAuth2AuthorizedClientExchangeFilterFunction` caches the token until it is within the
 * provider's clock skew of expiry and, when it cannot resolve a client at all, falls through to
 * `exchangeAndHandleResponse(request, next)` and sends the request with no Authorization header
 * rather than failing. Both arrive here as the same thing - a 401 we would otherwise surface to
 * the caller. Dropping the cached client forces the next exchange back to HMPPS Auth, so a stale
 * or missing token costs one extra round trip instead of a failed lookup.
 *
 * Must be registered *before* the authorising filter so that the retried exchange runs through it
 * again; `authorisedWebClient` appends its filter, so anything added via `builder.filters {}`
 * already sits outside it.
 *
 * Only GETs are retried: replaying a request body through a second exchange is not safe in
 * general, and this filter is only used on read-only clients.
 */
class RefreshTokenOnUnauthorizedFilter(
  private val registrationId: String,
  private val authorizedClientService: OAuth2AuthorizedClientService,
) : ExchangeFilterFunction {

  override fun filter(request: ClientRequest, next: ExchangeFunction): Mono<ClientResponse> = next.exchange(request).flatMap { response ->
    if (!shouldRetry(request, response)) {
      Mono.just(response)
    } else {
      log.warn(
        "{} returned 401 for {} {} - evicting the cached token and retrying once",
        registrationId,
        request.method(),
        request.url(),
      )
      // Drain the discarded response or the connection is never returned to the pool.
      response.releaseBody()
        .then(Mono.fromRunnable<Void> { authorizedClientService.removeAuthorizedClient(registrationId, GLOBAL_SYSTEM_PRINCIPAL) })
        .then(next.exchange(request))
    }
  }

  private fun shouldRetry(request: ClientRequest, response: ClientResponse) = response.statusCode().value() == HttpStatus.UNAUTHORIZED.value() && request.method() == HttpMethod.GET

  companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
