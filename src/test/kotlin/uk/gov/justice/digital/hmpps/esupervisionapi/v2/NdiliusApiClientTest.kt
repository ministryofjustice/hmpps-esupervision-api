package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import java.lang.reflect.InvocationTargetException
import java.net.ConnectException
import java.net.URI
import java.time.LocalDate
import kotlin.jvm.java
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker as CircuitBreakerAnnotation

/**
 * Resilience4j only invokes a @CircuitBreaker's fallbackMethod through the AOP proxy when the
 * circuit is actually open, which is impractical to trigger from a unit test. These tests instead
 * call the private fallback methods directly via reflection, to pin down their return behaviour
 * (fail-open vs fail-closed) so a future edit can't silently flip it.
 */
class NdiliusApiClientTest {

  private val client = NdiliusApiClient(WebClient.builder().build())

  @Test
  fun `strict calls use separate circuit breakers for general and eligibility traffic`() {
    assertEquals(
      "ndiliusApi",
      NdiliusApiClient::class.java.getDeclaredMethod("getContactDetailsStrict", String::class.java)
        .getAnnotation(CircuitBreakerAnnotation::class.java).name,
    )
    assertEquals(
      "ndiliusEligibilityApi",
      NdeliusEligibilityApiClient::class.java.getDeclaredMethod("getContactDetailsStrict", String::class.java)
        .getAnnotation(CircuitBreakerAnnotation::class.java).name,
    )
  }

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

  /**
   * ESUP-2181: returning false here told the person on probation their details were wrong when
   * NDelius was simply unreachable. A genuine 400 is handled inside the method and never gets here.
   */
  @Test
  fun `validatePersonalDetailsFallback fails closed on a transport failure, never reporting a mismatch`() {
    val transportFailure = WebClientRequestException(
      ConnectException("Connection refused"),
      HttpMethod.POST,
      URI.create("http://ndelius/case/X000001/validate-details"),
      HttpHeaders(),
    )

    val cause = invokeValidatePersonalDetailsFallback(transportFailure)

    assertEquals(PersonalDetailsVerificationUnavailableException::class.java, cause.javaClass)
    assertSame(transportFailure, cause.cause)
  }

  @Test
  fun `validatePersonalDetailsFallback fails closed when the circuit is open`() {
    val cause = invokeValidatePersonalDetailsFallback(openCircuit())

    assertEquals(PersonalDetailsVerificationUnavailableException::class.java, cause.javaClass)
  }

  private fun invokeValidatePersonalDetailsFallback(e: Exception): Throwable {
    val method = NdiliusApiClient::class.java.getDeclaredMethod(
      "validatePersonalDetailsFallback",
      PersonalDetails::class.java,
      Exception::class.java,
    )
    method.isAccessible = true
    val details = PersonalDetails("X000001", Name("John", "Smith"), LocalDate.of(1985, 5, 14))
    return assertThrows<InvocationTargetException> { method.invoke(client, details, e) }.targetException
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
