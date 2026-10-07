package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.exactly
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.core.functions.Either
import io.github.resilience4j.retry.RetryRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotifyGatewayService
import uk.gov.service.notify.NotificationClientApi
import uk.gov.service.notify.NotificationClientException

/**
 * Pins the resilience4j aspect nesting to CircuitBreaker( Retry( call ) ).
 *
 * With the library default, Retry( CircuitBreaker( call ) ), a fallbackMethod that returns a value
 * swallows the exception before Retry sees it, so `max-attempts` is silently ignored. Getting the
 * order backwards produces no error, so these assert observable behaviour: the number of upstream
 * attempts before the fallback runs, and the number of failures the breaker records.
 *
 * Uses the real backoff from application.yml, so each exhausted NDelius call takes ~1.5s and each
 * exhausted Notify call ~3s.
 */
class ResilienceAspectOrderIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var ndiliusApiClient: INdiliusApiClient

  @Autowired
  private lateinit var notifyGatewayService: NotifyGatewayService

  @Autowired
  private lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

  @Autowired
  private lateinit var retryRegistry: RetryRegistry

  @MockitoBean
  private lateinit var notificationClient: NotificationClientApi

  companion object {
    private const val CRN = "X990001"
    private const val USERNAME = "retry.user"

    private val upstreams = WireMockServer(options().dynamicPort()).apply { start() }

    @JvmStatic
    @DynamicPropertySource
    fun upstreamUrls(registry: DynamicPropertyRegistry) {
      registry.add("api.base.url.ndilius-api") { upstreams.baseUrl() }
    }

    @JvmStatic
    @AfterAll
    fun stopUpstreams() = upstreams.stop()
  }

  @BeforeEach
  fun reset() {
    upstreams.resetAll()
    hmppsAuth.stubGrantToken()
    reset(notificationClient)
    circuitBreakerRegistry.circuitBreaker("ndiliusApi").reset()
    circuitBreakerRegistry.circuitBreaker("govNotify").reset()
  }

  private fun stubContactDetails(status: Int) {
    upstreams.stubFor(get(urlEqualTo("/case/$CRN")).willReturn(aResponse().withStatus(status)))
  }

  @Test
  fun `NDelius call is attempted max-attempts times before the fallback value is returned`() {
    stubContactDetails(503)

    assertNull(ndiliusApiClient.getContactDetails(CRN))

    upstreams.verify(exactly(3), getRequestedFor(urlEqualTo("/case/$CRN")))
  }

  @Test
  fun `circuit breaker records one failure per exhausted NDelius call, not one per attempt`() {
    stubContactDetails(503)
    val breaker = circuitBreakerRegistry.circuitBreaker("ndiliusApi")

    ndiliusApiClient.getContactDetails(CRN)
    assertEquals(1, breaker.metrics.numberOfFailedCalls)

    ndiliusApiClient.getContactDetails(CRN)
    assertEquals(2, breaker.metrics.numberOfFailedCalls)
    upstreams.verify(exactly(6), getRequestedFor(urlEqualTo("/case/$CRN")))
  }

  @Test
  fun `a transient NDelius failure is retried and the real value returned, not the fallback`() {
    val url = "/user/$USERNAME/alerts"
    upstreams.stubFor(
      get(urlEqualTo(url)).inScenario("transient").whenScenarioStateIs(Scenario.STARTED)
        .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"),
    )
    upstreams.stubFor(
      get(urlEqualTo(url)).inScenario("transient").whenScenarioStateIs("recovered")
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""{"count": 4}""")),
    )

    assertEquals(4, ndiliusApiClient.getAlertCount(USERNAME))

    upstreams.verify(exactly(2), getRequestedFor(urlEqualTo(url)))
    val metrics = circuitBreakerRegistry.circuitBreaker("ndiliusApi").metrics
    assertEquals(0, metrics.numberOfFailedCalls)
    assertEquals(1, metrics.numberOfSuccessfulCalls)
  }

  @Test
  fun `Notify send is attempted max-attempts times before the fallback runs, recording one failure`() {
    whenever(notificationClient.sendSms(any(), any(), any(), any())).thenThrow(NotificationClientException("Notify unavailable"))

    assertThrows<NotificationClientException> {
      notifyGatewayService.sendSms("template-id", "07700900000", emptyMap(), "ref-1")
    }

    verify(notificationClient, times(3)).sendSms(any(), any(), any(), any())
    assertEquals(1, circuitBreakerRegistry.circuitBreaker("govNotify").metrics.numberOfFailedCalls)
  }

  @Test
  fun `retry backoff timings are unchanged`() {
    assertBackoff("ndiliusApi", 500, 1000)
    assertBackoff("govNotify", 1000, 2000)
  }

  private fun assertBackoff(name: String, vararg expectedWaitsMillis: Long) {
    val config = retryRegistry.retry(name).retryConfig
    assertEquals(3, config.maxAttempts, "$name max-attempts")
    val interval = config.getIntervalBiFunction<Any>()
    expectedWaitsMillis.forEachIndexed { i, expected ->
      assertEquals(expected, interval.apply(i + 1, Either.left(RuntimeException())), "$name wait before attempt ${i + 2}")
    }
  }
}
