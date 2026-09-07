package uk.gov.justice.digital.hmpps.esupervisionapi.v2.practitioner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient

class PractitionerResourceTest {

  private val ndiliusApiClient: INdiliusApiClient = mock()
  private lateinit var resource: PractitionerResource

  @BeforeEach
  fun setUp() {
    resource = PractitionerResource(ndiliusApiClient)
  }

  @Test
  fun `getAlertsByUsername - happy path - returns alert count`() {
    whenever(ndiliusApiClient.getAlertCount("test.user")).thenReturn(3)

    val result = resource.getAlertsByUsername("test.user")

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(3, result.body?.count)
  }

  @Test
  fun `getAlertsByUsername - username not found in ndilius - returns 404`() {
    whenever(ndiliusApiClient.getAlertCount("unknown.user")).thenReturn(null)

    val result = resource.getAlertsByUsername("unknown.user")

    assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
  }

  @Test
  fun `getAlertsByUsername - trims whitespace from username before calling client`() {
    whenever(ndiliusApiClient.getAlertCount("test.user")).thenReturn(3)

    val result = resource.getAlertsByUsername("  test.user  ")

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(3, result.body?.count)
  }

  @Test
  fun `getAlertsByUsername - client error from ndilius - propagates status`() {
    whenever(ndiliusApiClient.getAlertCount("test.user"))
      .thenThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not fetch alerts in NDelius for test.user."))

    val exception = assertThrows(ResponseStatusException::class.java) {
      resource.getAlertsByUsername("test.user")
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
  }

  @Test
  fun `getAlertsByUsername - client throws 503 - propagates status unchanged`() {
    whenever(ndiliusApiClient.getAlertCount("test.user"))
      .thenThrow(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Encountered an issue whilst fetching alerts in NDelius for test.user."))

    val exception = assertThrows(ResponseStatusException::class.java) {
      resource.getAlertsByUsername("test.user")
    }

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.statusCode)
  }
}
