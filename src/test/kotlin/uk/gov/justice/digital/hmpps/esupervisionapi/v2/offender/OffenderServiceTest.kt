package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.ArnsApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.ArnsWidget
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.RiskInSituation
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.TierApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.TierDetails
import java.time.LocalDate
import java.util.UUID

class OffenderServiceTest {

  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val tierApiClient: TierApiClient = mock()
  private val arnsApiClient: ArnsApiClient = mock()
  private val tierUiBaseUri: String = "http://tier-ui.local"
  private val crn: String = "X123456"
  private val contactDetails = ContactDetails(
    crn = crn,
    name = Name("John", "Doe"),
    email = "john@example.com",
    dateOfBirth = LocalDate.of(1980, 1, 1),
  )
  private val tierDetails = TierDetails(
    tierScore = "D2",
    calculationId = UUID.randomUUID(),
    calculationDate = LocalDate.of(2026, 1, 1),
    changeReason = "A registration was added",
  )
  private val riskInSituation = RiskInSituation(
    public = "HIGH",
    children = "LOW",
    knownAdult = "MEDIUM",
    staff = "VERY_HIGH",
    prisoners = null,
  )
  private val riskWidget = ArnsWidget(
    overallRisk = "VERY_HIGH",
    assessedOn = LocalDate.of(2026, 1, 1),
    riskInCommunity = riskInSituation,
    riskInCustody = riskInSituation,
  )

  private lateinit var service: OffenderService

  @BeforeEach
  fun setup() {
    service = OffenderService(
      ndiliusApiClient,
      tierApiClient,
      arnsApiClient,
      tierUiBaseUri,
    )
    whenever(ndiliusApiClient.getContactDetailsStrict(crn)).thenReturn(contactDetails)
    whenever(tierApiClient.getTierDetails(crn)).thenReturn(tierDetails)
    whenever(arnsApiClient.getRiskWidget(crn)).thenReturn(riskWidget)
  }

  @AfterEach
  fun teardown() {
    service.destroy()
  }

  private fun status(status: HttpStatus) = ResponseStatusException(status, "upstream said $status")

  @Test
  fun `getHeaderDetails - returns all details`() {
    val response = service.getHeaderDetails(crn)

    assertEquals(crn, response.crn)
    assertEquals(contactDetails.dateOfBirth, response.dateOfBirth)
    assertEquals(tierDetails.tierScore, response.tierScore)
    assertEquals("$tierUiBaseUri/case/$crn", response.tierDetailsLink)
    assertEquals(riskWidget.overallRisk, response.overallRisk)
    assertTrue(response.errors.isEmpty())
  }

  @Test
  fun `getHeaderDetails - NDelius CRN not found - throws 404 without calling other clients`() {
    whenever(ndiliusApiClient.getContactDetailsStrict(crn)).thenReturn(null)

    val e = assertThrows<ResponseStatusException> { service.getHeaderDetails(crn) }

    assertEquals(HttpStatus.NOT_FOUND, e.statusCode)
    assertEquals("Could not find contact details in NDelius for $crn.", e.reason)
    verify(tierApiClient, never()).getTierDetails(any())
    verify(arnsApiClient, never()).getRiskWidget(any())
  }

  @Test
  fun `getHeaderDetails - NDelius 404 response - throws 404`() {
    whenever(ndiliusApiClient.getContactDetailsStrict(crn)).thenThrow(status(HttpStatus.NOT_FOUND))

    val e = assertThrows<ResponseStatusException> { service.getHeaderDetails(crn) }

    assertEquals(HttpStatus.NOT_FOUND, e.statusCode)
  }

  @Test
  fun `getHeaderDetails - NDelius unavailable - degrades dateOfBirth`() {
    whenever(ndiliusApiClient.getContactDetailsStrict(crn)).thenThrow(status(HttpStatus.SERVICE_UNAVAILABLE))

    val response = service.getHeaderDetails(crn)

    assertNull(response.dateOfBirth)
    assertEquals(tierDetails.tierScore, response.tierScore)
    assertEquals(riskWidget.overallRisk, response.overallRisk)
    assertEquals(listOf(ErrorDetails("dateOfBirth", HeaderErrorCode.SERVICE_UNAVAILABLE)), response.errors)
  }

  @Test
  fun `getHeaderDetails - NDelius circuit open or connection failure - degrades dateOfBirth`() {
    whenever(ndiliusApiClient.getContactDetailsStrict(crn)).thenThrow(RuntimeException("CircuitBreaker 'ndiliusApi' is OPEN"))

    val response = service.getHeaderDetails(crn)

    assertNull(response.dateOfBirth)
    assertEquals(listOf(ErrorDetails("dateOfBirth", HeaderErrorCode.SERVICE_UNAVAILABLE)), response.errors)
  }

  @Test
  fun `getHeaderDetails - NDelius rejects request - reports REQUEST_REJECTED`() {
    whenever(ndiliusApiClient.getContactDetailsStrict(crn)).thenThrow(status(HttpStatus.FORBIDDEN))

    val response = service.getHeaderDetails(crn)

    assertNull(response.dateOfBirth)
    assertEquals(listOf(ErrorDetails("dateOfBirth", HeaderErrorCode.REQUEST_REJECTED)), response.errors)
  }

  @Test
  fun `getHeaderDetails - tier details not found`() {
    whenever(tierApiClient.getTierDetails(crn)).thenThrow(status(HttpStatus.NOT_FOUND))

    val response = service.getHeaderDetails(crn)

    assertEquals(contactDetails.dateOfBirth, response.dateOfBirth)
    assertNull(response.tierScore)
    assertEquals("$tierUiBaseUri/case/$crn", response.tierDetailsLink)
    assertEquals(riskWidget.overallRisk, response.overallRisk)
    assertEquals(listOf(ErrorDetails("tierScore", HeaderErrorCode.NOT_FOUND)), response.errors)
  }

  @Test
  fun `getHeaderDetails - tier client returns null - reported as NOT_FOUND`() {
    whenever(tierApiClient.getTierDetails(crn)).thenReturn(null)

    val response = service.getHeaderDetails(crn)

    assertNull(response.tierScore)
    assertEquals(listOf(ErrorDetails("tierScore", HeaderErrorCode.NOT_FOUND)), response.errors)
  }

  @Test
  fun `getHeaderDetails - tier details unavailable`() {
    whenever(tierApiClient.getTierDetails(crn)).thenThrow(status(HttpStatus.SERVICE_UNAVAILABLE))

    val response = service.getHeaderDetails(crn)

    assertNull(response.tierScore)
    assertEquals(riskWidget.overallRisk, response.overallRisk)
    assertEquals(listOf(ErrorDetails("tierScore", HeaderErrorCode.SERVICE_UNAVAILABLE)), response.errors)
  }

  @Test
  fun `getHeaderDetails - tier rejects request - reports REQUEST_REJECTED`() {
    whenever(tierApiClient.getTierDetails(crn)).thenThrow(status(HttpStatus.UNAUTHORIZED))

    val response = service.getHeaderDetails(crn)

    assertNull(response.tierScore)
    assertEquals(listOf(ErrorDetails("tierScore", HeaderErrorCode.REQUEST_REJECTED)), response.errors)
  }

  @Test
  fun `getHeaderDetails - tier client throws unexpectedly - reported as SERVICE_UNAVAILABLE`() {
    whenever(tierApiClient.getTierDetails(crn)).thenThrow(IllegalStateException("JSON decoding error"))

    val response = service.getHeaderDetails(crn)

    assertNull(response.tierScore)
    assertEquals(listOf(ErrorDetails("tierScore", HeaderErrorCode.SERVICE_UNAVAILABLE)), response.errors)
  }

  @Test
  fun `getHeaderDetails - ARNS widget has no overall risk - reported as NOT_FOUND`() {
    whenever(arnsApiClient.getRiskWidget(crn)).thenReturn(riskWidget.copy(overallRisk = null))

    val response = service.getHeaderDetails(crn)

    assertEquals(tierDetails.tierScore, response.tierScore)
    assertNull(response.overallRisk)
    assertEquals(listOf(ErrorDetails("overallRisk", HeaderErrorCode.NOT_FOUND)), response.errors)
  }

  @Test
  fun `getHeaderDetails - risk details not found`() {
    whenever(arnsApiClient.getRiskWidget(crn)).thenThrow(status(HttpStatus.NOT_FOUND))

    val response = service.getHeaderDetails(crn)

    assertEquals(contactDetails.dateOfBirth, response.dateOfBirth)
    assertEquals(tierDetails.tierScore, response.tierScore)
    assertNull(response.overallRisk)
    assertEquals(listOf(ErrorDetails("overallRisk", HeaderErrorCode.NOT_FOUND)), response.errors)
  }

  @Test
  fun `getHeaderDetails - risk details unavailable`() {
    whenever(arnsApiClient.getRiskWidget(crn)).thenThrow(status(HttpStatus.SERVICE_UNAVAILABLE))

    val response = service.getHeaderDetails(crn)

    assertNull(response.overallRisk)
    assertEquals(listOf(ErrorDetails("overallRisk", HeaderErrorCode.SERVICE_UNAVAILABLE)), response.errors)
  }

  @Test
  fun `getHeaderDetails - all clients error - errors listed in field order`() {
    whenever(ndiliusApiClient.getContactDetailsStrict(crn)).thenThrow(status(HttpStatus.SERVICE_UNAVAILABLE))
    whenever(tierApiClient.getTierDetails(crn)).thenThrow(status(HttpStatus.SERVICE_UNAVAILABLE))
    whenever(arnsApiClient.getRiskWidget(crn)).thenThrow(status(HttpStatus.SERVICE_UNAVAILABLE))

    val response = service.getHeaderDetails(crn)

    assertEquals(crn, response.crn)
    assertNull(response.dateOfBirth)
    assertNull(response.tierScore)
    assertEquals("$tierUiBaseUri/case/$crn", response.tierDetailsLink)
    assertNull(response.overallRisk)
    assertEquals(
      listOf(
        ErrorDetails("dateOfBirth", HeaderErrorCode.SERVICE_UNAVAILABLE),
        ErrorDetails("tierScore", HeaderErrorCode.SERVICE_UNAVAILABLE),
        ErrorDetails("overallRisk", HeaderErrorCode.SERVICE_UNAVAILABLE),
      ),
      response.errors,
    )
  }
}
