package uk.gov.justice.digital.hmpps.esupervisionapi.stats

import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerSiteRepository

data class PractitionerRegistrationInfo(
  val practitioner: ExternalUserId,
  val siteName: String,
  val registrationCount: Long,
)

@Service
class StatsService(val offenderRepository: OffenderRepository, val siteRepository: PractitionerSiteRepository) {
  fun practitionerRegistrations(): List<PractitionerRegistrationInfo> {
    // get registration counts by practitioner
    val registrations = offenderRepository.findPractitionerRegistrations()

    return registrations.map {
      val practitioner = it.practitioner
      val site = siteRepository.findLocation(practitioner)
      val siteName = site?.name ?: UNKNOWN_LOCATION_NAME

      PractitionerRegistrationInfo(
        practitioner,
        siteName,
        registrationCount = it.registrationCount,
      )
    }.toList()
  }

  companion object {
    const val UNKNOWN_LOCATION_NAME = "UNKNOWN"
  }
}
