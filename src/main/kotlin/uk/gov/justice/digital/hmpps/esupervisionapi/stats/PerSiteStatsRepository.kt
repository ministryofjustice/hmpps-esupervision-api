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
  val checkinAverages: List<SiteCheckinAverage>,
  val automatedIdCheckAccuracy: List<IdCheckAccuracy>,
  val flaggedCheckinsPerSite: List<SiteCount>,
  val stoppedCheckinsPerSite: List<SiteCount>,
  val averageFlagsPerCheckinPerSite: List<SiteAverage>,
  val averageSupportRequestsPerSite: List<SiteAverage>,
)

private val emptyStats = Stats(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

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
  @Value("classpath:db/queries/stats_checkin_completed_average_per_site.sql") private val avgCompletedCheckinsPerSite: Resource,
  @Value("classpath:db/queries/stats_checkin_id_check_mismatch.sql") private val automatedIdCheckAccuracyResource: Resource,
  @Value("classpath:db/queries/stats_checkin_flagged_per_site.sql") private val flaggedCheckinsPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_offender_number_stopped_checkins.sql") private val stoppedCheckinsPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_checkin_flag_average_per_site.sql") private val averageFlagsPerCheckinPerSiteResource: Resource,
  @Value("classpath:db/queries/stats_checkin_support_requests_average_per_site.sql") private val averageSupportRequestsPerSiteResource: Resource,

) : PerSiteStatsRepository {

  private val sqlInvitesPerSite: String by lazy { invitesPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlOffendersPerSite: String by lazy { offendersPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlCompletedCheckinsPerNthPerSite: String by lazy { completedCheckinsPerNthPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlCompletedCheckinsPerSite: String by lazy { completedCheckinsPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlAvgCompletedCheckinsPerSite: String by lazy { avgCompletedCheckinsPerSite.inputStream.use { it.reader().readText() } }
  private val sqlAutomatedIdCheckAccuracyResource: String by lazy { automatedIdCheckAccuracyResource.inputStream.use { it.reader().readText() } }
  private val sqlFlaggedCheckinsPerSite: String by lazy { flaggedCheckinsPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlStoppedCheckinsPerSite: String by lazy { stoppedCheckinsPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlAverageFlagsPerCheckinPerSite: String by lazy { averageFlagsPerCheckinPerSiteResource.inputStream.use { it.reader().readText() } }
  private val sqlAverageSupportRequestsPerSite: String by lazy { averageSupportRequestsPerSiteResource.inputStream.use { it.reader().readText() } }

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

    @Suppress("UNCHECKED_CAST")
    var rows = entityManager.createNativeQuery(sqlInvitesPerSite)
      .setParameter("lowerBound", lowerBound)
      .setParameter("upperBound", upperBound)
      .resultList as List<Array<Any?>>

    val invitesPerSite = rows.map { cols ->
      val location = cols[0] as String
      val count = (cols[1] as Number).toLong()
      SiteCount(location, count)
    }

    @Suppress("UNCHECKED_CAST")
    rows = entityManager.createNativeQuery(sqlOffendersPerSite)
      .setParameter("lowerBound", lowerBound)
      .setParameter("upperBound", upperBound)
      .resultList as List<Array<Any?>>

    val offendersPerSite = rows.map { cols ->
      val location = cols[0] as String
      val count = (cols[1] as Number).toLong()
      SiteCount(location, count)
    }

    @Suppress("UNCHECKED_CAST")
    rows = entityManager.createNativeQuery(sqlCompletedCheckinsPerSite)
      .setParameter("lowerBound", lowerBound)
      .setParameter("upperBound", upperBound)
      .resultList as List<Array<Any?>>

    val compledCheckinsPerSite = rows.map { cols ->
      val location = cols[0] as String
      val count = (cols[1] as Number).toLong()
      SiteCount(location, count)
    }

    @Suppress("UNCHECKED_CAST")
    rows = entityManager.createNativeQuery(sqlCompletedCheckinsPerNthPerSite)
      .setParameter("lowerBound", lowerBound)
      .setParameter("upperBound", upperBound)
      .resultList as List<Array<Any?>>

    val completedCheckinsPerNthPerSite = rows.map { cols ->
      val location = cols[0] as String
      val count = (cols[1] as Number).toLong()
      val nth = (cols[2] as Number).toLong()
      SiteCountOnNthDay(location, count, nth)
    }

    @Suppress("UNCHECKED_CAST")
    rows = entityManager.createNativeQuery(sqlAvgCompletedCheckinsPerSite)
      .setParameter("lowerBound", lowerBound)
      .setParameter("upperBound", upperBound)
      .resultList as List<Array<Any?>>

    val avgCompletedCheckinsPerSite = rows.map { cols ->
      val location = cols[0] as String
      val completedAvg = (cols[1] as Number).toLong()
      val completedStdDev = (cols[2] as Number).toLong()
      val expiredAvg = (cols[3] as Number).toLong()
      val expiredStdDev = (cols[4] as Number).toLong()
      val completedTotal = (cols[5] as Number).toLong()
      val expiredTotal = (cols[6] as Number).toLong()
      val missedPercentage = (cols[7] as Number).toDouble()
      SiteCheckinAverage(location, completedAvg, completedStdDev, expiredAvg, expiredStdDev, completedTotal, expiredTotal, missedPercentage)
    }

    @Suppress("UNCHECKED_CAST")
    rows = entityManager.createNativeQuery(sqlAutomatedIdCheckAccuracyResource)
      .setParameter("lowerBound", lowerBound)
      .setParameter("upperBound", upperBound)
      .resultList as List<Array<Any?>>

    val automatedIdCheckAccurracy = rows.map { cols ->
      val location = cols[0] as String
      val mismatchCount = (cols[1] as Number).toLong()
      val falsePositivesAvg = (cols[2] as Number).toLong()
      val falsePositiveStdDev = (cols[3] as Number).toLong()
      val falseNegativesAvg = (cols[4] as Number).toLong()
      val falseNegativesStdDev = (cols[5] as Number).toLong()
      IdCheckAccuracy(location, mismatchCount, falsePositivesAvg, falsePositiveStdDev, falseNegativesAvg, falseNegativesStdDev)
    }

    @Suppress("UNCHECKED_CAST")
    rows = entityManager.createNativeQuery(sqlFlaggedCheckinsPerSite)
      .setParameter("lowerBound", lowerBound)
      .setParameter("upperBound", upperBound)
      .resultList as List<Array<Any?>>

    val flaggedCheckinsPerSite = rows.map { cols ->
      val location = cols[0] as String
      val count = (cols[1] as Number).toLong()
      SiteCount(location, count)
    }

    @Suppress("UNCHECKED_CAST")
    rows = entityManager.createNativeQuery(sqlStoppedCheckinsPerSite)
      .setParameter("lowerBound", lowerBound)
      .setParameter("upperBound", upperBound)
      .resultList as List<Array<Any?>>

    val stoppedCheckinsPerSite = rows.map { cols ->
      val location = cols[0] as String
      val count = (cols[1] as Number).toLong()
      SiteCount(location, count)
    }

    @Suppress("UNCHECKED_CAST")
    rows = entityManager.createNativeQuery(sqlAverageFlagsPerCheckinPerSite)
      .setParameter("lowerBound", lowerBound)
      .setParameter("upperBound", upperBound)
      .resultList as List<Array<Any?>>

    val averageFlagsPerCheckinPerSite = rows.map { cols ->
      val location = cols[0] as String
      val average = (cols[1] as? Number)?.toDouble() ?: 0.0
      SiteAverage(location, average)
    }

    @Suppress("UNCHECKED_CAST")
    rows = entityManager.createNativeQuery(sqlAverageSupportRequestsPerSite)
      .setParameter("lowerBound", lowerBound)
      .setParameter("upperBound", upperBound)
      .resultList as List<Array<Any?>>

    val averageSupportRequestsPerSite = rows.map { cols ->
      val location = cols[0] as String
      val average = (cols[1] as? Number)?.toDouble() ?: 0.0
      SiteAverage(location, average)
    }

    return Stats(
      invitesPerSite = invitesPerSite,
      completedCheckinsPerSite = compledCheckinsPerSite,
      completedCheckinsPerNth = completedCheckinsPerNthPerSite,
      offendersPerSite = offendersPerSite,
      checkinAverages = avgCompletedCheckinsPerSite,
      automatedIdCheckAccuracy = automatedIdCheckAccurracy,
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
