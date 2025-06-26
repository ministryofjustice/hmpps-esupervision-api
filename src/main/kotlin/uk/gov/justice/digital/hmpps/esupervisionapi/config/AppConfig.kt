package uk.gov.justice.digital.hmpps.esupervisionapi.config

import java.net.URI
import java.util.UUID

class AppConfig(private val hostedAt: String) {
  // WARNING: this depends on the routes in the UI!
  fun checkinSubmitUrl(checkinUuid: UUID): URI = URI(
    "$hostedAt/submission/$checkinUuid",
  )

  // WARNING: this depends on the routes in the UI!
  fun checkinDashboardUrl(checkinUuid: UUID): URI = URI(
    "$hostedAt/practitioners/checkin/$checkinUuid",
  )
}
