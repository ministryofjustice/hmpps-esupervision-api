package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.client.WebClient
import java.lang.reflect.InvocationTargetException

/**
 * Resilience4j only invokes a @CircuitBreaker's fallbackMethod through the AOP proxy when the
 * circuit is actually open, which is impractical to trigger from a unit test. These tests instead
 * call the private fallback methods directly via reflection, to pin down their return behaviour
 * (fail-open vs fail-closed) so a future edit can't silently flip it.
 */
class NdiliusApiClientTest {

  private val client = NdiliusApiClient(WebClient.builder().build(), WebClient.builder().build())

  @Test
  fun `getAlertCountFallback fails open, returning null`() {
    val result = invokeFallback("getAlertCountFallback", "test.user")

    assertNull(result)
  }

  @Test
  fun `getContactDetailsFallback fails open, returning null`() {
    val result = invokeFallback("getContactDetailsFallback", "X000001")

    assertNull(result)
  }

  /**
   * The batch fetch is the one call that must never fail open: an empty list reads as "NDelius
   * holds none of these CRNs" and the jobs log a clean run. An open circuit is raised by the
   * aspect before the method body runs, so this fallback is the only thing that can turn it into
   * the [NdiliusBatchFetchException] callers catch per batch.
   */
  @Test
  fun `getContactDetailsForMultipleFallback fails closed, throwing with the CRNs intact`() {
    val crns = listOf("X000001", "X000002")
    val method = NdiliusApiClient::class.java.getDeclaredMethod(
      "getContactDetailsForMultipleFallback",
      List::class.java,
      CallNotPermittedException::class.java,
    )
    method.isAccessible = true

    val thrown = assertThrows<InvocationTargetException> { method.invoke(client, crns, openCircuit()) }

    val cause = thrown.targetException
    assertEquals(NdiliusBatchFetchException::class.java, cause.javaClass)
    assertEquals(crns, (cause as NdiliusBatchFetchException).crns)
  }

  private fun openCircuit(): CallNotPermittedException {
    val breaker = CircuitBreaker.ofDefaults("ndiliusApi")
    breaker.transitionToOpenState()
    return CallNotPermittedException.createCallNotPermittedException(breaker)
  }

  private fun invokeFallback(methodName: String, id: String): Any? {
    val method = NdiliusApiClient::class.java.getDeclaredMethod(methodName, String::class.java, Exception::class.java)
    method.isAccessible = true
    return method.invoke(client, id, RuntimeException("simulated circuit-open failure"))
  }
}
