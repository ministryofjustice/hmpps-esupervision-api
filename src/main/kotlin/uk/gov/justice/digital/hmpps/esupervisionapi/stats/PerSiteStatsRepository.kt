package uk.gov.justice.digital.hmpps.esupervisionapi.stats

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerSite
import java.time.Clock
import java.time.LocalDate

data class SiteCount(
  val location: String,
  val count: Long,
)

data class SiteAverage(
  val location: String,
  val average: Double,
)

/**
 * How many completed checkins were done on the nth day of the checkin window
 */
data class SiteCountOnNthDay(
  val location: String,
  val count: Long,
  val day: Long,
)

data class SiteCheckinFrequency(
  val location: String,
  val intervalDays: Long,
  val count: Long,
)

data class LabeledSiteCount(
  val location: String,
  val label: String,
  val count: Long,
  val total: Long? = null,
  val percentage: Double? = null,
)

data class LabeledNotificationSiteCount(
  val location: String,
  val messageType: String,
  val status: String,
  val count: Long,
)

data class SiteCheckinAverage(
  val location: String,
  val completedAvg: Long,
  val completedStdDev: Long,
  val expiredAvg: Long,
  val expiredStdDev: Long,
  val completedTotal: Long,
  val expiredTotal: Long,
  val missedPercentage: Double,
)

data class IntervalAverage(
  val location: String,
  val average: Long,
  val count: Long,
)

data class WeightedAverage(
  val location: String,
  val average: Double,
  val count: Long,
)

data class SiteFormattedTimeAverage(
  val location: String,
  var averageTimeText: String,
)

data class IdCheckAccuracy(
  val location: String,
  val mismatchCount: Long,
  val falsePositivesAvg: Long,
  val falsePositiveStdDev: Long,
  val falseNegativesAvg: Long,
  val falseNegativesStdDev: Long,
)

data class Stats(
  val invitesPerSite: List<SiteCount>,
  val inviteStatusPerSite: List<LabeledSiteCount>,
  val genericNotificationStatusPerSite: List<LabeledNotificationSiteCount>,
  val completedCheckinsPerSite: List<SiteCount>,
  val completedCheckinsPerNth: List<SiteCountOnNthDay>,
  val offendersPerSite: List<SiteCount>,
  val checkinFrequencyPerSite: List<SiteCheckinFrequency>,
  val checkinAverages: List<SiteCheckinAverage>,
  val ontimeCheckinPercentageTotal: Double,
  val checkinCompletedAverageTotal: Double,
  val checkinOutsideAccess: List<SiteCount>,
  val automatedIdCheckAccuracy: List<IdCheckAccuracy>,
  val flaggedCheckinsPerSite: List<SiteCount>,
  val stoppedCheckinsPerSite: List<SiteCount>,
  val averageFlagsPerCheckinPerSite: List<SiteAverage>,
  val averageFlagsPerCheckinTotal: Double,
  val callbackRequestPercentagePerSite: List<SiteAverage>,
  val callbackRequestPercentageTotal: Double,
  val averageReviewTimePerCheckinPerSite: List<SiteFormattedTimeAverage>,
  val averageReviewTimePerCheckinTotal: String,
  val averageTimeToRegisterPerSite: List<SiteFormattedTimeAverage>,
  val averageTimeToRegisterTotal: String,
  val averageCheckinCompletionTimePerSite: List<SiteFormattedTimeAverage>,
  val averageCheckinCompletionTimeTotal: String,
  val averageTimeTakenToCompleteCheckinReviewPerSite: List<SiteFormattedTimeAverage>,
  val averageTimeTakenToCompleteCheckinReviewTotal: String,
  val deviceType: List<LabeledSiteCount>,
)

private val emptyStats = Stats(
  emptyList(),
  emptyList(),
  emptyList(),
  emptyList(),
  emptyList(),
  emptyList(),
  emptyList(),
  emptyList(),
  0.0,
  0.0,
  emptyList(),
  emptyList(),
  emptyList(),
  emptyList(),
  emptyList(),
  0.0,
  emptyList(),
  0.0,
  emptyList(),
  String(),
  emptyList(),
  String(),
  emptyList(),
  String(),
  emptyList(),
  String(),
  emptyList(),
)

/**
 * Repository for running native Postgres queries over checkins.
 *
 * Notes on implementation approach:
 * - We create a temporary table bound to the DB session/transaction to hold practitioner->site assignments.
 * - We then reference that temp table from native queries to compute aggregates per site.
 * - Temp tables in PostgreSQL are connection-scoped; wrapping methods in a single @Transactional block ensures
 *   statements execute on the same connection so the temp table is visible for the subsequent SELECT.
 */
interface PerSiteStatsRepository {
  /**
   * Returns the number of sent checkin notifications per site (location), restricted to the provided
   * practitioner->site assignments.
   *
   * @param siteAssignments mapping of practitioner IDs to their site/location name
   */
  fun statsPerSite(siteAssignments: List<PractitionerSite>): Stats
}

@Repository
class PerSiteStatsRepositoryImpl(
  @PersistenceContext private val entityManager: EntityManager,
  private val siteAssignmentHelper: SiteAssignmentHelper,
  private val clock: Clock,
  @Value("classpath:db/queries/stats_checkin_invites_per_site.sql") private val invitesPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_checkin_invites_status_per_site.sql") private val invitesStatusPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_checkin_completed_per_site.sql") private val completedCheckinsPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_checkin_completed_on_nth_day.sql") private val completedCheckinsPerNthPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_offender_counts_per_site.sql") private val offendersPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_offender_checkin_frequency.sql") private val checkinFrequencyPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_checkin_completed_average_per_site.sql") private val avgCompletedCheckinsPerSite: Resource,
  @Value("classpath:db/queries/stats_checkin_id_check_mismatch.sql") private val automatedIdCheckAccuracyResource: Resource,
  @Value("classpath:db/queries/stats_checkin_flagged_per_site.sql") private val flaggedCheckinsPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_offender_number_stopped_checkins.sql") private val stoppedCheckinsPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_checkin_flag_and_support_average_per_site.sql") private val averageFlagsAndSupportRequestsPerCheckinPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_checkin_submission_to_review_time_average.sql") private val averageReviewResponseTimePerSiteResource: Resource,
  @Value("classpath:db/queries/stats_generic_offender_notifications_status_per_site.sql") private val genericNotificationsStatusPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_offender_checkin_device_type.sql") private val deviceTypeResource: Resource,
  @Value("classpath:db/queries/stats_offender_average_time_to_register.sql") private val averageTimeToRegisterResource: Resource,
  @Value("classpath:db/queries/stats_checkin_outside_access.sql") private val checkinOutsideAccessResource: Resource,
  @Value("classpath:db/queries/stats_checkin_completion_time_average.sql") private val averageSecondsToCompleteCheckinResource: Resource,
  @Value("classpath:db/queries/stats_checkin_average_time_to_complete_review.sql") private val averageTimeTakenToCompleteCheckinReviewPerSiteResource: Resource,

) : PerSiteStatsRepository {

  private val sqlInvitesPerSite: String by lazy { invitesPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlInvitesStatusPerSite: String by lazy { invitesStatusPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlOffendersPerSite: String by lazy { offendersPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlCheckinFrequencyPerSite: String by lazy { checkinFrequencyPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlCompletedCheckinsPerNthPerSite: String by lazy { completedCheckinsPerNthPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlCompletedCheckinsPerSite: String by lazy { completedCheckinsPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlAvgCompletedCheckinsPerSite: String by lazy { avgCompletedCheckinsPerSite.inputStream.use { it.reader().readText() } }
  private val sqlAutomatedIdCheckAccuracyResource: String by lazy { automatedIdCheckAccuracyResource.inputStream.use { it.reader().readText() } }
  private val sqlFlaggedCheckinsPerSite: String by lazy { flaggedCheckinsPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlStoppedCheckinsPerSite: String by lazy { stoppedCheckinsPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlAverageFlagsAndSupportRequestsPerCheckinPerSite: String by lazy { averageFlagsAndSupportRequestsPerCheckinPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlAverageReviewResponseTimePerSiteResource: String by lazy { averageReviewResponseTimePerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlGenericNotificationsStatusPerSite: String by lazy { genericNotificationsStatusPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlAverageTimeToRegister: String by lazy { averageTimeToRegisterResource.inputStream.use { it.reader().readText() } }
  private val sqlCheckinOutsideAccess: String by lazy { checkinOutsideAccessResource.inputStream.use { it.reader().readText() } }
  private val sqlAverageSecondsToCompleteCheckin: String by lazy { averageSecondsToCompleteCheckinResource.inputStream.use { it.reader().readText() } }
  private val sqlAverageTimeTakenToCompleteCheckinReviewPerSite: String by lazy { averageTimeTakenToCompleteCheckinReviewPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlDeviceType: String by lazy { deviceTypeResource.inputStream.use { it.reader().readText() } }

  @Transactional
  override fun statsPerSite(siteAssignments: List<PractitionerSite>): Stats {
    if (siteAssignments.isEmpty()) {
      return emptyStats
    }

    entityManager.createNativeQuery(createTempTable).executeUpdate()
    entityManager.createNativeQuery("truncate tmp_practitioner_sites").executeUpdate()
    siteAssignmentHelper.batchInsert(siteAssignments.map { SiteAssignment(it.practitioner, it.name) })

    val upperBound = LocalDate.now(clock.zone)
    val lowerBound = upperBound.minusMonths(6)
    val invitesPerSite = entityManager.runPerSiteQuery(sqlInvitesPerSite, lowerBound, upperBound).map(::siteCount)
    val invitesStatusPerSite = entityManager.runPerSiteQuery(sqlInvitesStatusPerSite, lowerBound, upperBound).map(::labeledSiteCount)
    val genericNotificationsStatusPerSite = entityManager.runPerSiteQuery(sqlGenericNotificationsStatusPerSite, lowerBound, upperBound).map(::genericNotificationStatus)
    val offendersPerSite = entityManager.runPerSiteQuery(sqlOffendersPerSite, lowerBound, upperBound).map(::siteCount)
    val completedCheckinsPerSite = entityManager.runPerSiteQuery(sqlCompletedCheckinsPerSite, lowerBound, upperBound).map(::siteCount)
    val completedCheckinsPerNthPerSite = entityManager.runPerSiteQuery(sqlCompletedCheckinsPerNthPerSite, lowerBound, upperBound).map(::siteCountOnNthDay)
    val avgCompletedCheckinsPerSite = entityManager.runPerSiteQuery(sqlAvgCompletedCheckinsPerSite, lowerBound, upperBound).map(::siteCheckinAverage)

    val ontimeCheckinPercentageTotal = calculateGlobalOntimePercentage(avgCompletedCheckinsPerSite)
    val totalCompletedCheckins = avgCompletedCheckinsPerSite.sumOf { it.completedTotal }
    val totalOffenders = offendersPerSite.sumOf { it.count }

    val checkinCompletedAverageTotal = calculateGlobalAverageCheckinsPerPoP(avgCompletedCheckinsPerSite, offendersPerSite)
    val checkinOutsideAccess = entityManager.runPerSiteQuery(sqlCheckinOutsideAccess, lowerBound, upperBound).map(::siteCount)
    val automatedIdCheckAccuracy = entityManager.runPerSiteQuery(sqlAutomatedIdCheckAccuracyResource, lowerBound, upperBound).map(::idCheckAccuracy)
    val flaggedCheckinsPerSite = entityManager.runPerSiteQuery(sqlFlaggedCheckinsPerSite, lowerBound, upperBound).map(::siteCount)
    val stoppedCheckinsPerSite = entityManager.runPerSiteQuery(sqlStoppedCheckinsPerSite, lowerBound, upperBound).map(::siteCount)
    val checkinFrequencyPerSite = entityManager.runPerSiteQuery(sqlCheckinFrequencyPerSite, lowerBound, upperBound).map(::siteCheckinFrequency)

    val flagsAndSupportRows = entityManager.runPerSiteQuery(sqlAverageFlagsAndSupportRequestsPerCheckinPerSite, lowerBound, upperBound)
    val flagStats = mutableListOf<WeightedAverage>()
    val callbackStats = mutableListOf<WeightedAverage>()

    for (row in flagsAndSupportRows) {
      val location = row[0] as String
      val avgFlags = (row[1] as? Number)?.toDouble() ?: 0.0
      val avgCallback = (row[2] as? Number)?.toDouble() ?: 0.0
      val count = (row[3] as Number).toLong()
      flagStats.add(WeightedAverage(location, avgFlags, count))
      callbackStats.add(WeightedAverage(location, avgCallback, count))
    }

    val averageFlagsPerCheckinPerSite = flagStats.map { SiteAverage(it.location, it.average) }
    val averageFlagsPerCheckinTotal = calculateWeightedAverageTotal(flagStats)

    val callbackRequestPercentagePerSite = callbackStats.map { SiteAverage(it.location, it.average) }
    val callbackRequestPercentageTotal = calculateWeightedAverageTotal(callbackStats)

    val reviewResponseTimes = entityManager.runPerSiteQuery(sqlAverageReviewResponseTimePerSiteResource, lowerBound, upperBound)
      .map(::intervalAverage)
    val averageReviewResponseTimes = reviewResponseTimes.map(::siteFormattedTimeAverage)
    val averageReviewResponseTimeTotal = siteFormattedTimeAverageTotal(reviewResponseTimes)

    val registrationTimes = entityManager.runPerSiteQuery(sqlAverageTimeToRegister, lowerBound, upperBound)
      .map(::intervalAverage)
    val averageTimeToRegisterPerSite = registrationTimes.map(::siteFormattedTimeAverage)
    val averageTimeToRegisterTotal = siteFormattedTimeAverageTotal(registrationTimes)

    val averageCheckinCompletionIntervals = entityManager.runPerSiteQuery(sqlAverageSecondsToCompleteCheckin, lowerBound, upperBound).map(::intervalAverage)
    val averageCheckinCompletionTimes = averageCheckinCompletionIntervals.map(::siteFormattedTimeAverage)
    val averageCheckinCompletionTimeTotal = siteFormattedTimeAverageTotal(averageCheckinCompletionIntervals)

    val reviewTimesToComplete = entityManager.runPerSiteQuery(sqlAverageTimeTakenToCompleteCheckinReviewPerSite, lowerBound, upperBound)
      .map(::intervalAverage)
    val averageTimeTakenToCompleteCheckinReviewPerSite = reviewTimesToComplete.map(::siteFormattedTimeAverage)
    val averageTimeTakenToCompleteCheckinReviewTotal = siteFormattedTimeAverageTotal(reviewTimesToComplete)

    val deviceTypePerSite = entityManager.runPerSiteQuery(sqlDeviceType, lowerBound, upperBound).map(::labeledSiteCountWithPercentage)

    return Stats(
      invitesPerSite = invitesPerSite,
      inviteStatusPerSite = invitesStatusPerSite,
      completedCheckinsPerSite = completedCheckinsPerSite,
      genericNotificationStatusPerSite = genericNotificationsStatusPerSite,
      completedCheckinsPerNth = completedCheckinsPerNthPerSite,
      offendersPerSite = offendersPerSite,
      checkinFrequencyPerSite = checkinFrequencyPerSite,
      checkinAverages = avgCompletedCheckinsPerSite,
      checkinCompletedAverageTotal = checkinCompletedAverageTotal,
      ontimeCheckinPercentageTotal = ontimeCheckinPercentageTotal,
      checkinOutsideAccess = checkinOutsideAccess,
      automatedIdCheckAccuracy = automatedIdCheckAccuracy,
      flaggedCheckinsPerSite = flaggedCheckinsPerSite,
      stoppedCheckinsPerSite = stoppedCheckinsPerSite,
      averageFlagsPerCheckinPerSite = averageFlagsPerCheckinPerSite,
      averageFlagsPerCheckinTotal = averageFlagsPerCheckinTotal,
      callbackRequestPercentagePerSite = callbackRequestPercentagePerSite,
      callbackRequestPercentageTotal = callbackRequestPercentageTotal,
      averageReviewTimePerCheckinPerSite = averageReviewResponseTimes,
      averageReviewTimePerCheckinTotal = averageReviewResponseTimeTotal,
      averageTimeToRegisterPerSite = averageTimeToRegisterPerSite,
      averageTimeToRegisterTotal = averageTimeToRegisterTotal,
      averageCheckinCompletionTimePerSite = averageCheckinCompletionTimes,
      averageCheckinCompletionTimeTotal = averageCheckinCompletionTimeTotal,
      averageTimeTakenToCompleteCheckinReviewPerSite = averageTimeTakenToCompleteCheckinReviewPerSite,
      averageTimeTakenToCompleteCheckinReviewTotal = averageTimeTakenToCompleteCheckinReviewTotal,
      deviceType = deviceTypePerSite,
    )
  }
}

open class SiteAssignment(var id: ExternalUserId, var siteName: String)

@Service
class SiteAssignmentHelper(private val jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate) {
  fun batchInsert(siteAssignments: List<SiteAssignment>) {
    jdbcTemplate.batchUpdate(
      "INSERT INTO tmp_practitioner_sites (practitioner, location) VALUES (?, ?)",
      object : BatchPreparedStatementSetter {
        override fun setValues(ps: java.sql.PreparedStatement, i: Int) {
          ps.setString(1, siteAssignments[i].id)
          ps.setString(2, siteAssignments[i].siteName)
        }
        override fun getBatchSize(): Int = siteAssignments.size
      },
    )
  }
}

val createTempTable = """
    create temporary table if not exists tmp_practitioner_sites (
      practitioner varchar not null,
      location varchar not null
    ) on commit delete rows
""".trimIndent()

private fun siteCheckinFrequency(cols: Array<Any?>): SiteCheckinFrequency = SiteCheckinFrequency(
  location = cols[0] as String,
  intervalDays = (cols[1] as Number).toLong(),
  count = (cols[2] as Number).toLong(),
)

private fun EntityManager.runPerSiteQuery(sql: String, lowerBound: LocalDate, upperBound: LocalDate): List<Array<Any?>> {
  @Suppress("UNCHECKED_CAST")
  return this.createNativeQuery(sql)
    .setParameter("lowerBound", lowerBound)
    .setParameter("upperBound", upperBound)
    .resultList as List<Array<Any?>>
}

private fun siteCount(cols: Array<Any?>): SiteCount = SiteCount(
  location = cols[0] as String,
  count = (cols[1] as Number).toLong(),
)

private fun siteCountOnNthDay(cols: Array<Any?>): SiteCountOnNthDay = SiteCountOnNthDay(
  location = cols[0] as String,
  count = (cols[1] as Number).toLong(),
  day = (cols[2] as Number).toLong(),
)

private fun siteCheckinAverage(cols: Array<Any?>): SiteCheckinAverage = SiteCheckinAverage(
  location = cols[0] as String,
  completedAvg = (cols[1] as Number).toLong(),
  completedStdDev = (cols[2] as Number).toLong(),
  expiredAvg = (cols[3] as Number).toLong(),
  expiredStdDev = (cols[4] as Number).toLong(),
  completedTotal = (cols[5] as Number).toLong(),
  expiredTotal = (cols[6] as Number).toLong(),
  missedPercentage = (cols[7] as Number).toDouble(),
)

private fun idCheckAccuracy(cols: Array<Any?>): IdCheckAccuracy = IdCheckAccuracy(
  location = cols[0] as String,
  mismatchCount = (cols[1] as Number).toLong(),
  falsePositivesAvg = (cols[2] as Number).toLong(),
  falsePositiveStdDev = (cols[3] as Number).toLong(),
  falseNegativesAvg = (cols[4] as Number).toLong(),
  falseNegativesStdDev = (cols[5] as Number).toLong(),
)

private fun siteAverage(location: Any?, average: Any?): SiteAverage = SiteAverage(
  location = location as String,
  average = (average as? Number)?.toDouble() ?: 0.0,
)

private fun labeledSiteCount(cols: Array<Any?>): LabeledSiteCount = LabeledSiteCount(
  location = cols[0] as String,
  label = cols[1] as String,
  count = (cols[2] as Number).toLong(),
)

private fun labeledSiteCountWithPercentage(cols: Array<Any?>): LabeledSiteCount = LabeledSiteCount(
  location = cols[0] as String,
  label = cols[1] as String,
  count = (cols[2] as Number).toLong(),
  total = (cols.getOrNull(3) as? Number)?.toLong(),
  percentage = (cols.getOrNull(4) as? Number)?.toDouble(),
)

private fun genericNotificationStatus(cols: Array<Any?>): LabeledNotificationSiteCount = LabeledNotificationSiteCount(
  location = cols[0] as String,
  messageType = cols[1] as String,
  status = cols[2] as String,
  count = cols[3] as Long,
)

private fun intervalAverage(cols: Array<Any?>): IntervalAverage = IntervalAverage(
  location = cols[0] as String,
  average = (cols[1] as? Number)?.toLong() ?: 0L,
  count = (cols[2] as Number).toLong(),
)

private fun siteFormattedTimeAverage(timeAverage: IntervalAverage): SiteFormattedTimeAverage = SiteFormattedTimeAverage(
  location = timeAverage.location,
  averageTimeText = String.format(
    "%sh%sm%ss",
    timeAverage.average / 3600,
    (timeAverage.average % 3600) / 60,
    timeAverage.average % 60,
  ),
)

private fun siteFormattedTimeAverageTotal(averagesPerSite: List<IntervalAverage>): String {
  if (averagesPerSite.isEmpty()) return "0h0m0s"
  var intervalTotal = 0L
  var count = 0L
  for (averagePerSite in averagesPerSite) {
    intervalTotal += (averagePerSite.average * averagePerSite.count)
    count += averagePerSite.count
  }

  if (count == 0L) return "0h0m0s"

  intervalTotal /= count

  return String.format(
    "%sh%sm%ss",
    intervalTotal / 3600,
    (intervalTotal % 3600) / 60,
    intervalTotal % 60,
  )
}

private fun calculateWeightedAverageTotal(weightedAverages: List<WeightedAverage>): Double {
  if (weightedAverages.isEmpty()) return 0.0
  var totalValue = 0.0
  var totalCount = 0L
  for (w in weightedAverages) {
    totalValue += (w.average * w.count)
    totalCount += w.count
  }
  return if (totalCount > 0) totalValue / totalCount else 0.0
}

private fun calculateGlobalOntimePercentage(averages: List<SiteCheckinAverage>): Double {
  val totalCompleted = averages.sumOf { it.completedTotal }
  val totalExpired = averages.sumOf { it.expiredTotal }
  val total = totalCompleted + totalExpired

  return if (total > 0) {
    (totalCompleted.toDouble() / total) * 100
  } else {
    0.0
  }
}

private fun calculateGlobalAverageCheckinsPerPoP(
  checkinAverages: List<SiteCheckinAverage>,
  offenderCounts: List<SiteCount>,
): Double {
  val totalCompletedCheckins = checkinAverages.sumOf { it.completedTotal }
  val totalOffenders = offenderCounts.sumOf { it.count }

  return if (totalOffenders > 0) {
    totalCompletedCheckins.toDouble() / totalOffenders
  } else {
    0.0
  }
}
