package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import uk.gov.service.notify.NotificationClientException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NotifyFailurePredicatesTest {

  private val recorded = NotifyTransientFailure()
  private val retried = NotifyRetryableFailure()

  @Nested
  inner class HttpStatuses {
    @ParameterizedTest
    @ValueSource(ints = [429, 500, 503])
    fun `failures Notify cannot have processed are retried and recorded`(status: Int) {
      assertEquals(true, retried.test(notifyFailure(status)))
      assertEquals(true, recorded.test(notifyFailure(status)))
    }

    /** A gateway error may arrive after Notify accepted the request, so resending risks a duplicate. */
    @ParameterizedTest
    @ValueSource(ints = [502, 504])
    fun `ambiguous gateway errors are recorded but not retried`(status: Int) {
      assertEquals(false, retried.test(notifyFailure(status)))
      assertEquals(true, recorded.test(notifyFailure(status)))
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404])
    fun `client errors are neither retried nor recorded`(status: Int) {
      assertEquals(false, retried.test(notifyFailure(status)))
      assertEquals(false, recorded.test(notifyFailure(status)))
    }

    @Test
    fun `a client-side validation failure, which Notify reports as 400, is neither retried nor recorded`() {
      val e = NotificationClientException("Invalid phone number")
      assertEquals(false, retried.test(e))
      assertEquals(false, recorded.test(e))
    }
  }

  @Nested
  inner class NetworkFailures {
    @Test
    fun `failing to connect is retried and recorded, since the request was never sent`() {
      listOf(ConnectException("Connection refused"), NoRouteToHostException("No route"), UnknownHostException("api.notifications.service.gov.uk"))
        .forEach {
          val e = NotificationClientException(it)
          assertEquals(true, retried.test(e), "retried: $it")
          assertEquals(true, recorded.test(e), "recorded: $it")
        }
    }

    @Test
    fun `failing after the request may have been sent is recorded but not retried`() {
      listOf(SocketTimeoutException("Read timed out"), SocketException("Connection reset"), IOException("Premature EOF"))
        .forEach {
          val e = NotificationClientException(it)
          assertEquals(false, retried.test(e), "retried: $it")
          assertEquals(true, recorded.test(e), "recorded: $it")
        }
    }
  }

  @Test
  fun `exceptions that are not from Notify are neither retried nor recorded`() {
    assertEquals(false, retried.test(IllegalStateException("boom")))
    assertEquals(false, recorded.test(IllegalStateException("boom")))
  }

  /** The (status, message) constructor Notify uses for HTTP errors is package-private. */
  private fun notifyFailure(status: Int): NotificationClientException = NotificationClientException::class.java
    .getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java)
    .apply { isAccessible = true }
    .newInstance(status, "Status code: $status")
}
