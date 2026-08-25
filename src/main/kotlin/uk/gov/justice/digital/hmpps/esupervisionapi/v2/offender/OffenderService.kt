package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.IArnsApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.ITierApiClient

@Service
class OffenderService(
  private val ndiliusApiClient: INdiliusApiClient,
  private val tierApiClient: ITierApiClient,
  private val arnsApiClient: IArnsApiClient,
  @Value("\${api.base.url.tier-ui}") val tierUiBaseUri: String,
) {

  fun getHeaderDetails(crn: String): OffenderHeaderDetails {
    val contactDetails = try {
      ndiliusApiClient.getContactDetails(crn)
        ?: throw ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "Could not verify contact details in NDelius for $crn.",
        )
    } catch (e: Exception) {
      LOGGER.error("Failed to fetch contact details from NDelius for CRN: $crn", e)
      null
    }

    val tierDetails = try {
      tierApiClient.getTierDetails(crn) ?: throw Exception()
    } catch (e: Exception) {
      LOGGER.error("Failed to fetch tier details from Tier API for CRN: $crn", e)
      null
    }

    val arnsWidget = try {
      arnsApiClient.getRiskWidget(crn) ?: throw Exception()
    } catch (e: Exception) {
      LOGGER.error("Failed to fetch risk widget from ARNS API for CRN: $crn", e)
      null
    }

    return OffenderHeaderDetails(
      crn = crn,
      dateOfBirth = contactDetails?.dateOfBirth,
      tierScore = tierDetails?.tierScore,
      tierDetailsLink = "$tierUiBaseUri/case/$crn",
      overallRisk = arnsWidget?.overallRisk,
    )
  }

  companion object {
    private val LOGGER = logger<OffenderService>()
  }
}
