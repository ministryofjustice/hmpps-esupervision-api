package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
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
  private val riskWidget = ArnsWidget(
    overallRisk = "VERY_HIGH",
    assessedOn = LocalDate.of(2026, 1, 1),
    riskInCommunity = RiskInSituation(
      public = "HIGH",
      children = "LOW",
      knownAdult = "MEDIUM",
      staff = "VERY_HIGH",
      prisoners = null,
    ),
    riskInCustody = RiskInSituation(
      public = "HIGH",
      children = "LOW",
      knownAdult = "MEDIUM",
      staff = "VERY_HIGH",
      prisoners = "VERY_HIGH",
    ),
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
  }

  @Test
  fun `getHeaderDetails - returns all details`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenReturn(contactDetails)
    whenever(tierApiClient.getTierDetails(crn)).thenReturn(tierDetails)
    whenever(arnsApiClient.getRiskWidget(crn)).thenReturn(riskWidget)

    val response = service.getHeaderDetails(crn)
    assertEquals(crn, response.crn)
    assertEquals(contactDetails.dateOfBirth, response.dateOfBirth)
    assertEquals(tierDetails.tierScore, response.tierScore)
    assertEquals("$tierUiBaseUri/case/$crn", response.tierDetailsLink)
    assertEquals(riskWidget.overallRisk, response.overallRisk)
  }

  @Test
  fun `getHeaderDetails - NDelius CRN not found`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenReturn(null)
    whenever(tierApiClient.getTierDetails(crn)).thenReturn(tierDetails)
    whenever(arnsApiClient.getRiskWidget(crn)).thenReturn(riskWidget)

    try {
      service.getHeaderDetails(crn)
    } catch (e: ResponseStatusException) {
      assertEquals(HttpStatus.NOT_FOUND, e.statusCode)
    }
  }

  @Test
  fun `getHeaderDetails - NDelius contact details client error`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenThrow(
      ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Could not verify contact details in NDelius for $crn.",
      ),
    )
    whenever(tierApiClient.getTierDetails(crn)).thenReturn(tierDetails)
    whenever(arnsApiClient.getRiskWidget(crn)).thenReturn(riskWidget)

    val response = service.getHeaderDetails(crn)

    assertEquals(crn, response.crn)
    assertNull(response.dateOfBirth)
    assertEquals(tierDetails.tierScore, response.tierScore)
    assertEquals("$tierUiBaseUri/case/$crn", response.tierDetailsLink)
    assertEquals(riskWidget.overallRisk, response.overallRisk)
    assertEquals("dateOfBirth", response.errors[0].field)
    assertEquals("SERVICE_UNAVAILABLE", response.errors[0].message)
  }

  @Test
  fun `getHeaderDetails - tier details not found`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenReturn(contactDetails)
    whenever(tierApiClient.getTierDetails(crn)).thenThrow(
      ResponseStatusException(
        HttpStatus.NOT_FOUND,
        "Could not verify tier details in Tier API for $crn.",
      ),
    )
    whenever(arnsApiClient.getRiskWidget(crn)).thenReturn(riskWidget)

    val response = service.getHeaderDetails(crn)

    assertEquals(crn, response.crn)
    assertEquals(contactDetails.dateOfBirth, response.dateOfBirth)
    assertNull(response.tierScore)
    assertEquals("$tierUiBaseUri/case/$crn", response.tierDetailsLink)
    assertEquals(riskWidget.overallRisk, response.overallRisk)
    assertEquals("tierScore", response.errors[0].field)
    assertEquals("NOT_FOUND", response.errors[0].message)
  }

  @Test
  fun `getHeaderDetails - tier details client error`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenReturn(contactDetails)
    whenever(tierApiClient.getTierDetails(crn)).thenThrow(
      ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Could not verify tier details in Tier API for $crn.",
      ),
    )
    whenever(arnsApiClient.getRiskWidget(crn)).thenReturn(riskWidget)

    val response = service.getHeaderDetails(crn)

    assertEquals(crn, response.crn)
    assertEquals(contactDetails.dateOfBirth, response.dateOfBirth)
    assertNull(response.tierScore)
    assertEquals("$tierUiBaseUri/case/$crn", response.tierDetailsLink)
    assertEquals(riskWidget.overallRisk, response.overallRisk)
    assertEquals("tierScore", response.errors[0].field)
    assertEquals("SERVICE_UNAVAILABLE", response.errors[0].message)
  }

  @Test
  fun `getHeaderDetails - ARNS returns empty risk details`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenReturn(contactDetails)
    whenever(tierApiClient.getTierDetails(crn)).thenReturn(tierDetails)

    val emptyArnsWidget = ArnsWidget()
    whenever(arnsApiClient.getRiskWidget(crn)).thenReturn(emptyArnsWidget)

    val response = service.getHeaderDetails(crn)

    assertEquals(crn, response.crn)
    assertEquals(contactDetails.dateOfBirth, response.dateOfBirth)
    assertEquals(tierDetails.tierScore, response.tierScore)
    assertEquals("$tierUiBaseUri/case/$crn", response.tierDetailsLink)
    assertNull(response.overallRisk)
  }

  @Test
  fun `getHeaderDetails - risk details not found`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenReturn(contactDetails)
    whenever(tierApiClient.getTierDetails(crn)).thenReturn(tierDetails)
    whenever(arnsApiClient.getRiskWidget(crn)).thenThrow(
      ResponseStatusException(
        HttpStatus.NOT_FOUND,
        "Could not verify tier details in Tier API for $crn.",
      ),
    )

    val response = service.getHeaderDetails(crn)

    assertEquals(crn, response.crn)
    assertEquals(contactDetails.dateOfBirth, response.dateOfBirth)
    assertEquals(tierDetails.tierScore, response.tierScore)
    assertEquals("$tierUiBaseUri/case/$crn", response.tierDetailsLink)
    assertNull(response.overallRisk)
    assertEquals("overallRisk", response.errors[0].field)
    assertEquals("NOT_FOUND", response.errors[0].message)
  }

  @Test
  fun `getHeaderDetails - risk details client error`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenReturn(contactDetails)
    whenever(tierApiClient.getTierDetails(crn)).thenReturn(tierDetails)
    whenever(arnsApiClient.getRiskWidget(crn)).thenThrow(
      ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Could not verify tier details in Tier API for $crn.",
      ),
    )

    val response = service.getHeaderDetails(crn)

    assertEquals(crn, response.crn)
    assertEquals(contactDetails.dateOfBirth, response.dateOfBirth)
    assertEquals(tierDetails.tierScore, response.tierScore)
    assertEquals("$tierUiBaseUri/case/$crn", response.tierDetailsLink)
    assertNull(response.overallRisk)
    assertEquals("overallRisk", response.errors[0].field)
    assertEquals("SERVICE_UNAVAILABLE", response.errors[0].message)
  }

  @Test
  fun `getHeaderDetails - all clients error`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenThrow(
      ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Could not verify contact details in NDelius for $crn.",
      ),
    )
    whenever(tierApiClient.getTierDetails(crn)).thenThrow(
      ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Could not verify tier details in Tier API for $crn.",
      ),
    )
    whenever(arnsApiClient.getRiskWidget(crn)).thenThrow(
      ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Could not verify tier details in Tier API for $crn.",
      ),
    )

    val response = service.getHeaderDetails(crn)

    assertEquals(crn, response.crn)
    assertNull(response.dateOfBirth)
    assertNull(response.tierScore)
    assertEquals("$tierUiBaseUri/case/$crn", response.tierDetailsLink)
    assertNull(response.overallRisk)
    assertEquals("dateOfBirth", response.errors[0].field)
    assertEquals("SERVICE_UNAVAILABLE", response.errors[0].message)
    assertEquals("tierScore", response.errors[1].field)
    assertEquals("SERVICE_UNAVAILABLE", response.errors[1].message)
    assertEquals("overallRisk", response.errors[2].field)
    assertEquals("SERVICE_UNAVAILABLE", response.errors[2].message)
  }
}
