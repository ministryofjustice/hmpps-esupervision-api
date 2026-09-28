package uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.client.WebClient
import java.lang.reflect.InvocationTargetException

/**
 * The fallback only runs through the resilience4j aspect, which a unit test cannot reach, so it is
 * invoked directly to pin its behaviour. It must fail closed: returning null would read as "unknown
 * CRN", and an outage would look like a person with no package and no recall.
 */
class SupervisionPackagesApiClientTest {

  private val client = SupervisionPackagesApiClient(WebClient.builder().build())

  @Test
  fun `getSupervisionPackageDetailsFallback fails closed, throwing with the CRN and cause intact`() {
    val cause = RuntimeException("simulated outage")
    val method = SupervisionPackagesApiClient::class.java.getDeclaredMethod(
      "getSupervisionPackageDetailsFallback",
      String::class.java,
      Exception::class.java,
    )
    method.isAccessible = true

    val thrown = assertThrows<InvocationTargetException> { method.invoke(client, "X000001", cause) }

    val exception = thrown.targetException as SupervisionPackagesFetchException
    assertEquals("X000001", exception.crn)
    assertSame(cause, exception.cause)
  }
}
