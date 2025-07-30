package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.util.UUID

@Component
class AppConfig(
  @Value("\${app.hostedAt}") private val hostedAt: String,
  @Value("\${app.scheduling.checkin-notification.cron}") val checkinNotificationCron: String,
) {
  // WARNING: this depends on the routes in the UI!
  fun checkinSubmitUrl(checkinUuid: UUID): URI = URI(
    "$hostedAt/submission/$checkinUuid",
  )

  // WARNING: this depends on the routes in the UI!
  fun checkinDashboardUrl(checkinUuid: UUID): URI = URI(
    "$hostedAt/practitioners/checkin/$checkinUuid",
  )

  // WARNING: this depends on the routes in the UI!
  fun dashboardUrl(): URI = URI(
    "$hostedAt/practitioners",
  )
}
