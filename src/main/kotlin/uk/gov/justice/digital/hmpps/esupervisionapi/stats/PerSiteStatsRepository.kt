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
  val completedCheckinsPerSite: List<SiteCount>,
  val completedCheckinsPerNth: List<SiteCountOnNthDay>,
  val offendersPerSite: List<SiteCount>,
  val checkinFrequencyPerSite: List<SiteCheckinFrequency>,
  val checkinAverages: List<SiteCheckinAverage>,
  val automatedIdCheckAccuracy: List<IdCheckAccuracy>,
  val flaggedCheckinsPerSite: List<SiteCount>,
  val stoppedCheckinsPerSite: List<SiteCount>,
  val averageFlagsPerCheckinPerSite: List<SiteAverage>,
  val averageSupportRequestsPerSite: List<SiteAverage>,
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
  emptyList(),
  emptyList(),
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
  @Value("classpath:db/queries/stats_checkin_completed_per_site.sql") private val completedCheckinsPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_checkin_completed_on_nth_day.sql") private val completedCheckinsPerNthPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_offender_counts_per_site.sql") private val offendersPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_offender_checkin_frequency.sql") private val checkinFrequencyPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_checkin_completed_average_per_site.sql") private val avgCompletedCheckinsPerSite: Resource,
  @Value("classpath:db/queries/stats_checkin_id_check_mismatch.sql") private val automatedIdCheckAccuracyResource: Resource,
  @Value("classpath:db/queries/stats_checkin_flagged_per_site.sql") private val flaggedCheckinsPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_offender_number_stopped_checkins.sql") private val stoppedCheckinsPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_checkin_flag_and_support_average_per_site.sql") private val averageFlagsAndSupportRequestsPerCheckinPerSiteResource: Resource,

) : PerSiteStatsRepository {

  private val sqlInvitesPerSite: String by lazy { invitesPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlOffendersPerSite: String by lazy { offendersPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlCheckinFrequencyPerSite: String by lazy { checkinFrequencyPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlCompletedCheckinsPerNthPerSite: String by lazy { completedCheckinsPerNthPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlCompletedCheckinsPerSite: String by lazy { completedCheckinsPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlAvgCompletedCheckinsPerSite: String by lazy { avgCompletedCheckinsPerSite.inputStream.use { it.reader().readText() } }
  private val sqlAutomatedIdCheckAccuracyResource: String by lazy { automatedIdCheckAccuracyResource.inputStream.use { it.reader().readText() } }
  private val sqlFlaggedCheckinsPerSite: String by lazy { flaggedCheckinsPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlStoppedCheckinsPerSite: String by lazy { stoppedCheckinsPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlAverageFlagsAndSupportRequestsPerCheckinPerSite: String by lazy { averageFlagsAndSupportRequestsPerCheckinPerSiteResource.inputStream.use { it.reader().readText() } }

  @Transactional
  override fun statsPerSite(siteAssignments: List<PractitionerSite>): Stats {
    if (siteAssignments.isEmpty()) {
      return emptyStats
    }

    entityManager.createNativeQuery(createTempTable).executeUpdate()
    entityManager.createNativeQuery("truncate tmp_practitioner_sites").executeUpdate()
    siteAssignmentHelper.batchInsert(siteAssignments.map { SiteAssignment(it.practitioner, it.name) })

    val lowerBound = LocalDate.of(2025, 1, 1)
    val upperBound = LocalDate.now(clock.zone)

    val invitesPerSite = entityManager.runPerSiteQuery(sqlInvitesPerSite, lowerBound, upperBound).map(::siteCount)
    val offendersPerSite = entityManager.runPerSiteQuery(sqlOffendersPerSite, lowerBound, upperBound).map(::siteCount)
    val completedCheckinsPerSite = entityManager.runPerSiteQuery(sqlCompletedCheckinsPerSite, lowerBound, upperBound).map(::siteCount)
    val completedCheckinsPerNthPerSite = entityManager.runPerSiteQuery(sqlCompletedCheckinsPerNthPerSite, lowerBound, upperBound).map(::siteCountOnNthDay)
    val avgCompletedCheckinsPerSite = entityManager.runPerSiteQuery(sqlAvgCompletedCheckinsPerSite, lowerBound, upperBound).map(::siteCheckinAverage)
    val automatedIdCheckAccuracy = entityManager.runPerSiteQuery(sqlAutomatedIdCheckAccuracyResource, lowerBound, upperBound).map(::idCheckAccuracy)
    val flaggedCheckinsPerSite = entityManager.runPerSiteQuery(sqlFlaggedCheckinsPerSite, lowerBound, upperBound).map(::siteCount)
    val stoppedCheckinsPerSite = entityManager.runPerSiteQuery(sqlStoppedCheckinsPerSite, lowerBound, upperBound).map(::siteCount)
    val checkinFrequencyPerSite = entityManager.runPerSiteQuery(sqlCheckinFrequencyPerSite, lowerBound, upperBound).map(::siteCheckinFrequency)

    val flagsAndSupport = entityManager.runPerSiteQuery(sqlAverageFlagsAndSupportRequestsPerCheckinPerSite, lowerBound, upperBound)
    val averageFlagsPerCheckinPerSite = mutableListOf<SiteAverage>()
    val averageSupportRequestsPerSite = mutableListOf<SiteAverage>()
    for (row in flagsAndSupport) {
      averageFlagsPerCheckinPerSite.add(siteAverage(row[0], row[1]))
      averageSupportRequestsPerSite.add(siteAverage(row[0], row[2]))
    }

    return Stats(
      invitesPerSite = invitesPerSite,
      completedCheckinsPerSite = completedCheckinsPerSite,
      completedCheckinsPerNth = completedCheckinsPerNthPerSite,
      offendersPerSite = offendersPerSite,
      checkinFrequencyPerSite = checkinFrequencyPerSite,
      checkinAverages = avgCompletedCheckinsPerSite,
      automatedIdCheckAccuracy = automatedIdCheckAccuracy,
      flaggedCheckinsPerSite = flaggedCheckinsPerSite,
      stoppedCheckinsPerSite = stoppedCheckinsPerSite,
      averageFlagsPerCheckinPerSite = averageFlagsPerCheckinPerSite,
      averageSupportRequestsPerSite = averageSupportRequestsPerSite,
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
