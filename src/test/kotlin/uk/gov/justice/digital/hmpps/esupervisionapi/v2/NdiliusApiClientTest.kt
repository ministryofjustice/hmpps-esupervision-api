package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

/**
 * Resilience4j only invokes a @CircuitBreaker's fallbackMethod through the AOP proxy when the
 * circuit is actually open, which is impractical to trigger from a unit test. These tests instead
 * call the private fallback methods directly via reflection, to pin down their return behaviour
 * (fail-open vs fail-closed) so a future edit can't silently flip it.
 */
class NdiliusApiClientTest {

  private val client = NdiliusApiClient(WebClient.builder().build())

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

  private fun invokeFallback(methodName: String, id: String): Any? {
    val method = NdiliusApiClient::class.java.getDeclaredMethod(methodName, String::class.java, Exception::class.java)
    method.isAccessible = true
    return method.invoke(client, id, RuntimeException("simulated circuit-open failure"))
  }
}
