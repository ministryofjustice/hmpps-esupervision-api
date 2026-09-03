package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.IArnsApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.ITierApiClient
import java.util.ArrayList

@Service
class OffenderService(
  private val ndiliusApiClient: INdiliusApiClient,
  private val tierApiClient: ITierApiClient,
  private val arnsApiClient: IArnsApiClient,
  @Value("\${api.base.url.tier-ui}") val tierUiBaseUri: String,
) {

  fun getHeaderDetails(crn: String): OffenderHeaderDetails {
    var errors = ArrayList<ErrorDetails>()

    val contactDetails = try {
      ndiliusApiClient.getContactDetails(crn)
        ?: throw ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "Could not find contact details in NDelius for $crn.",
        )
    } catch (e: ResponseStatusException) {
      LOGGER.error("Failed to fetch contact details from NDelius for CRN: {}", PiiSanitizer.sanitizeException(e, crn))
      if (e.statusCode == HttpStatus.NOT_FOUND) {
        throw e
      }
      errors.add(ErrorDetails("dateOfBirth", "SERVICE_UNAVAILABLE"))
      null
    } catch (e: Exception) {
      LOGGER.error("Failed to fetch contact details from NDelius for CRN: {}", PiiSanitizer.sanitizeException(e, crn))
      errors.add(ErrorDetails("dateOfBirth", "SERVICE_UNAVAILABLE"))
      null
    }

    val tierDetails = try {
      tierApiClient.getTierDetails(crn)
    } catch (e: ResponseStatusException) {
      LOGGER.error("Failed to fetch tier details from Tier API for CRN: {}", PiiSanitizer.sanitizeException(e, crn))
      if (e.statusCode == HttpStatus.NOT_FOUND) {
        errors.add(ErrorDetails("tierScore", "NOT_FOUND"))
      }
      else errors.add(ErrorDetails("tierScore", "SERVICE_UNAVAILABLE"))
      null
    } catch (e: Exception) {
      LOGGER.error("Failed to fetch tier details from Tier API for CRN: {}", PiiSanitizer.sanitizeException(e, crn))
      errors.add(ErrorDetails("tierScore", "SERVICE_UNAVAILABLE"))
      null
    }

    val arnsWidget = try {
      arnsApiClient.getRiskWidget(crn)
    } catch (e: ResponseStatusException) {
      LOGGER.error("Failed to fetch risk widget from ARNS API for CRN:  {}", PiiSanitizer.sanitizeException(e, crn))
      if (e.statusCode == HttpStatus.NOT_FOUND) {
        errors.add(ErrorDetails("overallRisk", "NOT_FOUND"))
      }
      else errors.add(ErrorDetails("overallRisk", "SERVICE_UNAVAILABLE"))
      null
    } catch (e: Exception) {
      errors.add(ErrorDetails("overallRisk", "SERVICE_UNAVAILABLE"))
      LOGGER.error("Failed to fetch risk widget from ARNS API for CRN:  {}", PiiSanitizer.sanitizeException(e, crn))
      null
    }

    return OffenderHeaderDetails(
      crn = crn,
      dateOfBirth = contactDetails?.dateOfBirth,
      tierScore = tierDetails?.tierScore,
      tierDetailsLink = "$tierUiBaseUri/case/$crn",
      overallRisk = arnsWidget?.overallRisk,
      errors = errors,
    )
  }

  companion object {
    private val LOGGER = logger<OffenderService>()
  }
}
