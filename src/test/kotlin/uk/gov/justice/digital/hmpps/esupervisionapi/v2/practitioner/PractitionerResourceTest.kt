package uk.gov.justice.digital.hmpps.esupervisionapi.v2.practitioner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
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
    whenever(ndiliusApiClient.getAlertCount("sarah.johnson")).thenReturn(3)

    val result = resource.getAlertsByUsername("sarah.johnson")

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(3, result.body?.count)
  }

  @Test
  fun `getAlertsByUsername - username not found in ndilius - returns 404`() {
    whenever(ndiliusApiClient.getAlertCount("unknown.user")).thenReturn(null)

    val result = resource.getAlertsByUsername("unknown.user")

    assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
  }
}
