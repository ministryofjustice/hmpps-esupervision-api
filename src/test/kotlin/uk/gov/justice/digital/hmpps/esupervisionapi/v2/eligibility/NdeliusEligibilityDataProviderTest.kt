package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import java.time.LocalDate
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors

class NdeliusEligibilityDataProviderTest {

  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val executor = Executors.newSingleThreadExecutor()
  private val provider = NdeliusEligibilityDataProvider(ndiliusApiClient, executor)

  @Test
  fun `sourceKey is NDELIUS`() {
    assertEquals("NDELIUS", provider.sourceKey)
  }

  @Disabled("deceasedDate not yet available in NDelius integration")
  @Test
  fun `fetch maps deceasedDate and current event from contact details`() {
    val event = Event(number = 1L, mainOffence = CodedDescription("X", "An offence"), sentence = null)
    whenever(ndiliusApiClient.getContactDetails("X123456")).thenReturn(
      ContactDetails(
        crn = "X123456",
        name = Name("John", "Doe"),
        dateOfBirth = LocalDate.of(1980, 1, 1),
        events = listOf(event),
        // deceasedDate = LocalDate.of(2024, 1, 1),
      ),
    )

    val result = provider.fetch("X123456").join()

    assertEquals(LocalDate.of(2024, 1, 1), result["DECEASED_DATE"])
    assertEquals(event, result["ACTIVE_EVENT"])
  }

  @Test
  fun `failure to fetch data from NDelius completes exceptionally`() {
    whenever(ndiliusApiClient.getContactDetails("X123456")).thenReturn(null)

    assertThrows(CompletionException::class.java) {
      provider.fetch("X123456").join()
    }
  }

  @Test
  fun `fetch completes exceptionally when the client throws`() {
    whenever(ndiliusApiClient.getContactDetails(any())).thenThrow(RuntimeException("NDelius down"))

    val future = provider.fetch("X123456")

    assertThrows(CompletionException::class.java) { future.join() }
  }
}
