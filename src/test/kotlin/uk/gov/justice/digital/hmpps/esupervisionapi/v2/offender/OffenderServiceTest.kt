package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
  private val tierApiBaseUri: String = "http://tier-api.local"
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
      tierApiBaseUri,
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
    assertEquals("$tierApiBaseUri/case/$crn", response.tierDetailsLink)
    assertEquals(riskWidget.overallRisk, response.overallRisk)
  }

  @Test
  fun `getHeaderDetails - returns all details when there is no risk info`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenReturn(contactDetails)
    whenever(tierApiClient.getTierDetails(crn)).thenReturn(tierDetails)

    val emptyArnsWidget = ArnsWidget()
    whenever(arnsApiClient.getRiskWidget(crn)).thenReturn(emptyArnsWidget)

    val response = service.getHeaderDetails(crn)

    assertEquals(crn, response.crn)
    assertEquals(contactDetails.dateOfBirth, response.dateOfBirth)
    assertEquals(tierDetails.tierScore, response.tierScore)
    assertEquals("$tierApiBaseUri/case/$crn", response.tierDetailsLink)
    assertEquals("NOT_FOUND", response.overallRisk)
  }

  @Test
  fun `getHeaderDetails - contact details missing`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenReturn(null)

    val exception = assertThrows(ResponseStatusException::class.java) {
      val response = service.getHeaderDetails(crn)
    }

    assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
    assertEquals("Could not verify contact details in NDelius for $crn.", exception.reason)
  }

  @Test
  fun `getHeaderDetails - tier details missing`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenReturn(contactDetails)
    whenever(tierApiClient.getTierDetails(crn)).thenReturn(null)

    val exception = assertThrows(ResponseStatusException::class.java) {
      val response = service.getHeaderDetails(crn)
    }

    assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
    assertEquals("Could not verify tier details in Tier API for $crn.", exception.reason)
  }

  @Test
  fun `getHeaderDetails - risk details missing`() {
    whenever(ndiliusApiClient.getContactDetails(crn)).thenReturn(contactDetails)
    whenever(tierApiClient.getTierDetails(crn)).thenReturn(tierDetails)
    whenever(arnsApiClient.getRiskWidget(crn)).thenReturn(null)

    val exception = assertThrows(ResponseStatusException::class.java) {
      val response = service.getHeaderDetails(crn)
    }

    assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
    assertEquals("Could not verify risk widget in ARNS API for $crn.", exception.reason)
  }
}
