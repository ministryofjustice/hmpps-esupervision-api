package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import uk.gov.justice.hmpps.kotlin.auth.service.GlobalPrincipalOAuth2AuthorizedClientService.Companion.GLOBAL_SYSTEM_PRINCIPAL

/**
 * Attaches a client-credentials bearer to calls made with no servlet request in scope.
 *
 * `ServletOAuth2AuthorizedClientExchangeFilterFunction` does not resolve an authorized client from
 * the manager directly - it reads a Reactor context key that Spring Security's
 * `SecurityReactorContextSubscriber` fills in at subscribe time from the *subscribing thread's*
 * request and security context. From Spring Security 7.1 that resolution yields nothing when there
 * is no request in scope, and `filter()` then sends the request unauthenticated rather than
 * failing, so the upstream answers 401. Under 7.0.6 the same call went out with a bearer.
 *
 * The scheduled jobs are exactly that case, and unlike `OffenderService.onBehalfOfRequest` - which
 * rebinds a real request onto a thread spawned *inside* one - they have no request to rebind. So
 * this filter goes to the manager itself, under the same [GLOBAL_SYSTEM_PRINCIPAL] the request path
 * caches against, meaning background and request callers share one token rather than minting two.
 *
 * Deliberately inert whenever a request *is* in scope, or the caller has already set its own
 * header: the request path keeps its existing behaviour untouched, and this only fills the gap the
 * authorising filter leaves. It is not restricted to GETs - it adds a header rather than replaying
 * a body, so a batch POST is as safe as a lookup.
 *
 * Register *inside* [RefreshTokenOnUnauthorizedFilter] (i.e. added after it), so that filter's
 * retry re-enters this one and picks up a freshly minted token rather than replaying the evicted
 * one.
 */
class BackgroundClientCredentialsFilter(
  private val registrationId: String,
  private val authorizedClientManager: OAuth2AuthorizedClientManager,
) : ExchangeFilterFunction {

  override fun filter(request: ClientRequest, next: ExchangeFunction): Mono<ClientResponse> {
    // Read on the subscribing thread, which is where the authorising filter would look too.
    if (RequestContextHolder.getRequestAttributes() != null || request.headers().getFirst(HttpHeaders.AUTHORIZATION) != null) {
      return next.exchange(request)
    }
    // authorize() blocks on the token endpoint when the cache is cold, so keep it off the caller's
    // thread; switchIfEmpty covers a registration that cannot be authorised at all, leaving the
    // request to fail against the upstream exactly as it did before rather than here.
    return Mono.fromCallable { authorize() }
      .subscribeOn(Schedulers.boundedElastic())
      .flatMap { token -> next.exchange(ClientRequest.from(request).headers { it.setBearerAuth(token) }.build()) }
      .switchIfEmpty(Mono.defer { next.exchange(request) })
  }

  private fun authorize(): String? {
    val authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
      .principal(backgroundPrincipal)
      .build()
    val token = authorizedClientManager.authorize(authorizeRequest)?.accessToken?.tokenValue
    if (token == null) {
      log.warn("No client credentials could be obtained for {} on a background call", registrationId)
    }
    return token
  }

  companion object {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Only the name is load-bearing: it is the key the authorized-client store caches against, and
     * matching the request path's key is what keeps the two sharing a token.
     */
    private val backgroundPrincipal: Authentication = AnonymousAuthenticationToken(
      "esupervision-background",
      GLOBAL_SYSTEM_PRINCIPAL,
      AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"),
    )
  }
}
