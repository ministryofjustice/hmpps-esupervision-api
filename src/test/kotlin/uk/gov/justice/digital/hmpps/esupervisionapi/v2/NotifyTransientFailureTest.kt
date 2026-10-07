package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import uk.gov.service.notify.NotificationClientException
import java.io.IOException

class NotifyTransientFailureTest {

  private val predicate = NotifyTransientFailure()

  @ParameterizedTest
  @ValueSource(ints = [429, 500, 502, 503, 504])
  fun `rate limiting and server errors are transient`(status: Int) {
    assertTrue(predicate.test(notifyFailure(status)))
  }

  @ParameterizedTest
  @ValueSource(ints = [400, 401, 403, 404])
  fun `client errors are not transient`(status: Int) {
    assertFalse(predicate.test(notifyFailure(status)))
  }

  @Test
  fun `a network failure, which carries no HTTP status, is transient`() {
    assertTrue(predicate.test(NotificationClientException(IOException("Connection reset"))))
  }

  @Test
  fun `a client-side validation failure, which Notify reports as 400, is not transient`() {
    assertFalse(predicate.test(NotificationClientException("Invalid phone number")))
  }

  @Test
  fun `exceptions that are not from Notify are not transient`() {
    assertFalse(predicate.test(IllegalStateException("boom")))
  }

  /** The (status, message) constructor Notify uses for HTTP errors is package-private. */
  private fun notifyFailure(status: Int): NotificationClientException = NotificationClientException::class.java
    .getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java)
    .apply { isAccessible = true }
    .newInstance(status, "Status code: $status")
}
