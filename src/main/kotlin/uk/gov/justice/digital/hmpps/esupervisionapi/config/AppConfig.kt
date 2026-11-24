package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import java.net.URI
import java.util.UUID

@Component
class AppConfig(
  @Value("\${app.checkinUrl}") private val checkinUrl: String,
  @Value("\${app.mpopUrl}") private val mpopUrl: String,
  @Value("\${app.hostedAt}") private val hostedAt: String,
  @Value("\${app.features.esup-1239}") val esup1239ProxyLinks: Boolean,
  @Value("\${app.features.esup-1763}") val esup1763RemoveSnapshots: Boolean,
  @Value($$"${app.features.esup-2082:false}") val esup2082NewEligibilityRules: Boolean,
  @Value($$"${app.features.esup-2305:false}") val esup2305DeliusEventSelection: Boolean,
  @Value($$"${app.features.esup-2223:false}") val esup2223UpcomingCheckins: Boolean,
  @Value($$"${app.offender-eligibility.sync-dry-run:false}") val eligibilitySyncDryRun: Boolean,
  val enabledFeatures: Set<Feature> = listOfNotNull(
    if (esup1239ProxyLinks) Feature.ESUP_1239 else null,
    if (esup1763RemoveSnapshots) Feature.ESUP_1763 else null,
    if (esup2082NewEligibilityRules) Feature.ESUP_2082 else null,
    if (esup2305DeliusEventSelection) Feature.ESUP_2305 else null,
    if (esup2223UpcomingCheckins) Feature.ESUP_2223 else null,
  ).toSet(),
) {

  init {
    LOG.info("Enabled features: $enabledFeatures")
  }

  /**
   * Does the nightly eligibility sync job stop people? Only with ESUP_2082 on and not as a dry run.
   * Until it does, the check-in creation and reminder jobs keep stopping people who have no active
   * sentence or are in reset; once it does, they leave those people to it. The sync job's CronJob
   * must also be unsuspended in the same release, or no one stops them - the app can't see that.
   */
  val eligibilitySyncStopsPeople: Boolean
    get() = Feature.ESUP_2082 in enabledFeatures && !eligibilitySyncDryRun

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

  // WARNING: this depends on the routes in the V2 POP UI!
  fun checkinSubmitUrlV2(checkinUuid: UUID): URI = URI(
    "$checkinUrl/$checkinUuid",
  )

  // WARNING: this depends on the routes in MPOP!
  fun checkinReviewUrlV2(checkinUuid: UUID, crn: String): URI = URI(
    "$mpopUrl/case/$crn/appointments/$checkinUuid/check-in/review/identity?back=/case/$crn/activity-log",
  )

  fun feedbackUrl(): URI = URI(
    "$checkinUrl/feedback",
  )

  fun addQuestionsUrl(offenderUuid: UUID, crn: CRN): URI = URI(
    "$mpopUrl/case/$crn/appointments/check-in/manage/$offenderUuid/questions/start",
  )

  // example: https://manage-people-on-probation-dev.hmpps.service.justice.gov.uk/case/Y083911/activity-log
  fun contactsUrl(crn: CRN): URI = URI(
    "$mpopUrl/case/$crn/activity-log",

  )

  fun mediaProxyUrl(): URI = URI("$hostedAt/resolve")

  companion object {
    val LOG = org.slf4j.LoggerFactory.getLogger(this::class.java)
  }
}
