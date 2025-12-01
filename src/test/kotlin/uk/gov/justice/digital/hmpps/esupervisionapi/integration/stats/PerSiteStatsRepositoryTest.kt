package uk.gov.justice.digital.hmpps.esupervisionapi.integration.stats

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.PRACTITIONER_ALICE
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.PRACTITIONER_BOB
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.PRACTITIONER_DAVE
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.create
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.GenericNotification
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.GenericNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinNotification
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.LogEntryType
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderEventLog
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerSite
import uk.gov.justice.digital.hmpps.esupervisionapi.stats.LabeledSiteCount
import uk.gov.justice.digital.hmpps.esupervisionapi.stats.PerSiteStatsRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.stats.SiteCount
import uk.gov.justice.digital.hmpps.esupervisionapi.stats.SiteCountOnNthDay
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.powerSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

class PerSiteStatsRepositoryTest : IntegrationTestBase() {

  private data class CheckinParam(
    val offender: Offender,
    val createdBy: ExternalUserId,
    val status: CheckinStatus,
    val dueDate: LocalDate? = null,
    val autoIdCheck: AutomatedIdVerificationResult? = null,
    val submittedAt: Instant? = null,
    val checkinStartedAt: Instant? = null,
    val device: Map<String, Object>? = null,
  )

  private fun createCheckins(params: List<CheckinParam>): List<OffenderCheckin> = params.map createCheckin@{
    val checkin = OffenderCheckin.create(
      offender = it.offender,
      createdBy = it.createdBy,
      status = it.status,
      autoIdCheck = it.autoIdCheck,
      submittedAt = it.submittedAt,
      checkinStartedAt = it.checkinStartedAt,
    )
    if (it.dueDate != null) {
      checkin.dueDate = it.dueDate
    }
    if (it.device != null) {
      checkin.surveyResponse = mapOf("device" to it.device as Object)
    }

    return@createCheckin checkin
  }

  @Autowired
  lateinit var genericNotificationRepository: GenericNotificationRepository

  @Autowired
  lateinit var perSiteStatsRepository: PerSiteStatsRepository

  @Autowired
  lateinit var cacheManager: CacheManager

  @BeforeEach
  fun setup() {
    cacheManager.getCache("stats")?.clear()
    genericNotificationRepository.deleteAll()
    checkinNotificationRepository.deleteAll()
    checkinRepository.deleteAll()
    offenderEventLogRepository.deleteAll()
    offenderSetupRepository.deleteAll()
    offenderRepository.deleteAll()
  }

  @Test
  fun `counts sent checkins per site using temp table`() {
    val practitionerId: ExternalUserId = PRACTITIONER_ALICE.externalUserId()
    val siteAssignments = listOf(PractitionerSite(practitionerId, "Site A"), PractitionerSite("bob", "Site B"))

    val offender = offenderRepository.save(
      Offender.create(
        name = "Bob Offerman",
        crn = "X12345",
        firstCheckinDate = LocalDate.now().minusDays(10),
        practitioner = PRACTITIONER_ALICE,
        status = OffenderStatus.VERIFIED,
      ),
    )

    val checkins = listOf(
      OffenderCheckin.create(
        offender = offender,
        createdBy = practitionerId,
        status = CheckinStatus.SUBMITTED,
        dueDate = LocalDate.now().minusDays(5),
        submittedAt = ZonedDateTime.now().minusDays(4).toInstant(),
      ),
      OffenderCheckin.create(
        offender = offender,
        createdBy = practitionerId,
        status = CheckinStatus.EXPIRED,
        dueDate = LocalDate.now().minusDays(1),
      ),
    )

    val checkin = checkinRepository.saveAll(checkins)

    checkinNotificationRepository.saveAll(
      listOf(
        CheckinNotification(
          notificationId = UUID.randomUUID(),
          checkin = checkin[0].uuid,
          reference = "manual-test",
          status = "sending",
        ),
        CheckinNotification(
          notificationId = UUID.randomUUID(),
          checkin = checkin[1].uuid,
          reference = "manual-test",
          status = "sending",
        ),
      ),
    )

    val counts = perSiteStatsRepository.statsPerSite(siteAssignments)

    assertThat(counts.invitesPerSite).containsExactlyInAnyOrder(
      SiteCount("Site A", 2),
    )
  }

  @Test
  fun `location with no expired checkins`() {
    val practitionerId: ExternalUserId = PRACTITIONER_ALICE.externalUserId()
    val siteAssignments = listOf(PractitionerSite(practitionerId, "Site A"))

    val offender = offenderRepository.save(
      Offender.create(
        name = "Bob Carr",
        crn = "X12344",
        firstCheckinDate = LocalDate.now().minusDays(9),
        practitioner = PRACTITIONER_ALICE,
      ),
    )

    val checkins = listOf(
      OffenderCheckin.create(
        offender = offender,
        createdBy = practitionerId,
        status = CheckinStatus.SUBMITTED,
        dueDate = LocalDate.now().minusDays(4),
        submittedAt = ZonedDateTime.now().minusDays(3).toInstant(),
      ),
    )

    val checkin = checkinRepository.saveAll(checkins)

    checkinNotificationRepository.saveAll(
      listOf(
        CheckinNotification(
          notificationId = UUID.randomUUID(),
          checkin = checkin[0].uuid,
          reference = "manual-test",
          status = "sending",
        ),
      ),
    )

    val counts = perSiteStatsRepository.statsPerSite(siteAssignments)

    assertThat(counts.checkinAverages[0].expiredTotal).isEqualTo(0)
    assertThat(counts.invitesPerSite).containsExactlyInAnyOrder(
      SiteCount("Site A", 1),
    )
  }

  @Test
  fun `location with no review mismatches`() {
    val practitionerId: ExternalUserId = PRACTITIONER_ALICE.externalUserId()
    val siteAssignments = listOf(PractitionerSite(practitionerId, "Site A"))

    val offender = offenderRepository.save(
      Offender.create(
        name = "Bob Carr",
        crn = "X12344",
        firstCheckinDate = LocalDate.now().minusDays(9),
        practitioner = PRACTITIONER_ALICE,
      ),
    )

    val checkins = listOf(
      OffenderCheckin.create(
        offender = offender,
        createdBy = practitionerId,
        status = CheckinStatus.REVIEWED,
        dueDate = LocalDate.now().minusDays(4),
        submittedAt = ZonedDateTime.now().minusDays(3).toInstant(),
        manualIdCheck = ManualIdVerificationResult.MATCH,
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
        reviewedBy = practitionerId,
      ),
    )

    checkinRepository.saveAll(checkins)

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)

    assertThat(stats.automatedIdCheckAccuracy[0].mismatchCount).isEqualTo(0)
  }

  @Test
  fun `checkins on nth day`() {
    val practitionerId: ExternalUserId = PRACTITIONER_ALICE.externalUserId()
    val siteAssignments = listOf(PractitionerSite(practitionerId, "Site A"))

    val checkinStart = LocalDate.now().minusDays(28)

    // NOTE: checkin time each day shouldn't matter
    val checkinTime = OffsetTime.of(14, 37, 0, 0, ZoneOffset.UTC)

    val offender = offenderRepository.save(
      Offender.create(
        name = "Bob Carr",
        crn = "X12344",
        firstCheckinDate = checkinStart,
        practitioner = PRACTITIONER_ALICE,
      ),
    )

    // create 5 checkins
    // - 2 completed on day 1
    // - 1 completed on day 2
    // - 1 completed on day 3
    // - 1 uncompleted
    val checkinsDue = (0..<5).map { checkinStart.plusDays(it * 4L) }
    val checkinDayOffsets = listOf(0L, 1L, 2L, 0L, null)

    val checkins = checkinsDue.zip(checkinDayOffsets).map { (checkinDue, checkinOffset) ->
      val status = if (checkinOffset == null) CheckinStatus.CREATED else CheckinStatus.SUBMITTED
      val submittedAt = if (checkinOffset == null) null else checkinDue.plusDays(checkinOffset)
      OffenderCheckin.create(
        offender = offender,
        createdBy = practitionerId,
        status = status,
        dueDate = checkinDue,
        submittedAt = submittedAt?.atTime(checkinTime)?.toInstant(),
      )
    }.toList()

    checkinRepository.saveAll(checkins)

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)

    val checkinDays = stats.completedCheckinsPerNth
    assertThat(checkinDays).containsExactlyInAnyOrder(
      SiteCountOnNthDay(location = "Site A", day = 1, count = 2),
      SiteCountOnNthDay(location = "Site A", day = 2, count = 1),
      SiteCountOnNthDay(location = "Site A", day = 3, count = 1),
    )
  }

  @Test
  fun `checkin flagged for mental health`() {
    val practitionerId: ExternalUserId = PRACTITIONER_ALICE.externalUserId()
    val siteAssignments = listOf(PractitionerSite(practitionerId, "Site A"))

    val checkinStart = LocalDate.now().minusDays(28)

    val offender = offenderRepository.save(
      Offender.create(
        name = "Bob Carr",
        crn = "X12344",
        firstCheckinDate = checkinStart,
        practitioner = PRACTITIONER_ALICE,
      ),
    )

    // create 5 checkins, 2 with mental health flag in the survey
    val checkins = listOf("NOT_GREAT", "OK", "STRUGGLING", "WELL", "VERY_WELL").mapIndexed { offsetDays, mentalHealth ->
      val date = checkinStart.plusDays(offsetDays.toLong())
      OffenderCheckin.create(
        offender = offender,
        createdBy = practitionerId,
        status = CheckinStatus.SUBMITTED,
        dueDate = date,
        submittedAt = date.atTime(OffsetTime.of(12, 11, 0, 0, ZoneOffset.UTC)).toInstant(),
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot" as Object,
          "mentalHealth" to mentalHealth as Object,
          "assistance" to listOf("NO_HELP") as Object,
          "callback" to "NO" as Object,
        ),
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
      )
    }.toList()

    checkinRepository.saveAll(checkins)

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)

    val flaggedCheckins = stats.flaggedCheckinsPerSite
    assertThat(flaggedCheckins).containsExactlyInAnyOrder(SiteCount("Site A", 2))
  }

  @Test
  fun `checkin flagged for autoId verification failure`() {
    val practitionerId: ExternalUserId = PRACTITIONER_ALICE.externalUserId()
    val siteAssignments = listOf(PractitionerSite(practitionerId, "Site A"))

    val checkinStart = LocalDate.now().minusDays(28)

    val offender = offenderRepository.save(
      Offender.create(
        name = "Bob Carr",
        crn = "X12344",
        firstCheckinDate = checkinStart,
        practitioner = PRACTITIONER_ALICE,
      ),
    )

    // create two checkins, one with a passing auto id check, one without
    // survey otherwise contains no flags
    val checkins =
      listOf(AutomatedIdVerificationResult.MATCH, AutomatedIdVerificationResult.NO_MATCH).mapIndexed { offsetDays, vr ->
        val date = checkinStart.plusDays(offsetDays.toLong())
        OffenderCheckin.create(
          offender = offender,
          createdBy = practitionerId,
          status = CheckinStatus.SUBMITTED,
          dueDate = date,
          submittedAt = date.atTime(OffsetTime.of(12, 11, 0, 0, ZoneOffset.UTC)).toInstant(),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot" as Object,
            "mentalHealth" to "OK" as Object,
            "assistance" to listOf("NO_HELP") as Object,
            "callback" to "NO" as Object,
          ),
          autoIdCheck = vr,
        )
      }

    checkinRepository.saveAll(checkins)

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)

    val flaggedCheckins = stats.flaggedCheckinsPerSite
    assertThat(flaggedCheckins).containsExactlyInAnyOrder(SiteCount("Site A", 1))
  }

  @Test
  fun `checkin not flagged`() {
    val practitionerId: ExternalUserId = PRACTITIONER_ALICE.externalUserId()
    val siteAssignments = listOf(PractitionerSite(practitionerId, "Site A"))

    val checkinStart = LocalDate.now().minusDays(28)

    val offender = offenderRepository.save(
      Offender.create(
        name = "Bob Carr",
        crn = "X12344",
        firstCheckinDate = checkinStart,
        practitioner = PRACTITIONER_ALICE,
      ),
    )

    val date = LocalDate.now()

    checkinRepository.save(
      OffenderCheckin.create(
        offender = offender,
        createdBy = practitionerId,
        status = CheckinStatus.SUBMITTED,
        dueDate = date,
        submittedAt = date.atTime(OffsetTime.of(12, 11, 0, 0, ZoneOffset.UTC)).toInstant(),
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot" as Object,
          "mentalHealth" to "OK" as Object,
          "assistance" to listOf("NO_HELP") as Object,
          "callback" to "NO" as Object,
        ),
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
      ),
    )

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)

    val flaggedCheckins = stats.flaggedCheckinsPerSite
    assertThat(flaggedCheckins).containsExactlyInAnyOrder(SiteCount("Site A", 0))
  }

  @Test
  fun `multiple checkin flags counted once`() {
    // create checkin with multiple flags
    // checkin with multiple flags should only contribute 1 to the site count
    val practitionerId: ExternalUserId = PRACTITIONER_ALICE.externalUserId()
    val siteAssignments = listOf(PractitionerSite(practitionerId, "Site A"))

    val checkinStart = LocalDate.now().minusDays(28)

    val offender = offenderRepository.save(
      Offender.create(
        name = "Bob Carr",
        crn = "X12344",
        firstCheckinDate = checkinStart,
        practitioner = PRACTITIONER_ALICE,
      ),
    )

    val date = LocalDate.now()

    checkinRepository.save(
      OffenderCheckin.create(
        offender = offender,
        createdBy = practitionerId,
        status = CheckinStatus.REVIEWED,
        dueDate = date,
        submittedAt = date.atTime(OffsetTime.of(12, 11, 0, 0, ZoneOffset.UTC)).toInstant(),
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot" as Object,
          "mentalHealth" to "NOT_GREAT" as Object,
          "assistance" to listOf("DRUGS", "ALCOHOL", "OTHER") as Object,
          "callback" to "YES" as Object,
        ),
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
      ),
    )

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)

    val flaggedCheckins = stats.flaggedCheckinsPerSite
    assertThat(flaggedCheckins).containsExactlyInAnyOrder(SiteCount("Site A", 1))
  }

  @Test
  fun `checkin flagged for assistance`() {
    val practitionerId: ExternalUserId = PRACTITIONER_ALICE.externalUserId()
    val siteAssignments = listOf(PractitionerSite(practitionerId, "Site A"))

    val checkinStart = LocalDate.now().minusDays(28)

    val offender = offenderRepository.save(
      Offender.create(
        name = "Bob Carr",
        crn = "X12344",
        firstCheckinDate = checkinStart,
        practitioner = PRACTITIONER_ALICE,
      ),
    )

    val supportAspects = listOf("MENTAL_HEALTH", "ALCOHOL", "DRUGS", "MONEY", "HOUSING", "OTHER")

    // create checkin for each permutation of support requested
    val supportPerms = supportAspects.powerSet()

    val checkins = supportPerms.mapIndexed { offsetDays, support ->
      val date = checkinStart.plusDays(offsetDays.toLong())

      // if no support is requested, 'NO_HELP' is specified
      val assistance = if (support.isEmpty()) listOf("NO_HELP") else support.toList()

      OffenderCheckin.create(
        offender = offender,
        createdBy = practitionerId,
        status = CheckinStatus.SUBMITTED,
        dueDate = date,
        submittedAt = date.atTime(OffsetTime.of(12, 11, 0, 0, ZoneOffset.UTC)).toInstant(),
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot" as Object,
          "mentalHealth" to "OK" as Object,
          "assistance" to assistance as Object,
          "callback" to "NO" as Object,
        ),
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
      )
    }

    checkinRepository.saveAll(checkins)

    // NOTE: 1 checkin should contain no flags and specify 'NO_HELP'. The rest should all specify at least one flag
    // each such checkin should only contribute 1 to the resulting count even if multiple requests for support are made
    val expectedFlaggedCheckins = supportPerms.count { it.isNotEmpty() }.toLong()

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)

    val flaggedCheckins = stats.flaggedCheckinsPerSite
    assertThat(flaggedCheckins).containsExactlyInAnyOrder(SiteCount("Site A", expectedFlaggedCheckins))
  }

  @Test
  fun `checkin flagged for callback`() {
    val practitionerId: ExternalUserId = PRACTITIONER_ALICE.externalUserId()
    val siteAssignments = listOf(PractitionerSite(practitionerId, "Site A"))

    val checkinStart = LocalDate.now().minusDays(28)

    val offender = offenderRepository.save(
      Offender.create(
        name = "Bob Carr",
        crn = "X12344",
        firstCheckinDate = checkinStart,
        practitioner = PRACTITIONER_ALICE,
      ),
    )

    // create two checkins, one with a request for a callback, the other without
    // survey otherwise contains no flags
    val checkins = listOf("YES", "NO").mapIndexed { offsetDays, callback ->
      val date = checkinStart.plusDays(offsetDays.toLong())
      OffenderCheckin.create(
        offender = offender,
        createdBy = practitionerId,
        status = CheckinStatus.SUBMITTED,
        dueDate = date,
        submittedAt = date.atTime(OffsetTime.of(12, 11, 0, 0, ZoneOffset.UTC)).toInstant(),
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot" as Object,
          "mentalHealth" to "OK" as Object,
          "assistance" to listOf("NO_HELP") as Object,
          "callback" to callback as Object,
        ),
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
      )
    }

    checkinRepository.saveAll(checkins)

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)

    val flaggedCheckins = stats.flaggedCheckinsPerSite
    assertThat(flaggedCheckins).containsExactlyInAnyOrder(SiteCount("Site A", 1))
  }

  @Test
  fun `checkin flags at multiple sites`() {
    val practitioner1: ExternalUserId = PRACTITIONER_ALICE.externalUserId()
    val practitioner2: ExternalUserId = PRACTITIONER_BOB.externalUserId()
    val practitioner3: ExternalUserId = PRACTITIONER_DAVE.externalUserId()

    val siteAssignments = listOf(
      PractitionerSite(practitioner1, "Site A"),
      PractitionerSite(practitioner2, "Site B"),
      PractitionerSite(practitioner3, "Site C"),
    )

    val checkinStart = LocalDate.now().minusDays(28)

    // create PoP at each site
    val offender1 = Offender.create(
      name = "Bob Carr",
      crn = "X123786",
      firstCheckinDate = checkinStart,
      practitioner = PRACTITIONER_ALICE,
    )

    val offender2 = Offender.create(
      name = "Dave Smith",
      crn = "X987432",
      firstCheckinDate = checkinStart,
      practitioner = PRACTITIONER_BOB,
    )

    val offender3 = Offender.create(
      name = "Jon Jones",
      crn = "A000003",
      firstCheckinDate = checkinStart,
      practitioner = PRACTITIONER_DAVE,
    )

    offenderRepository.saveAll(listOf(offender1, offender2, offender3))

    // create checkins at each site
    // site A - 3 checkins with one flag each
    // site B - 1 checkin with no flags, 1 with multiple flags
    // site C - 1 checkin with no flags
    val checkins = listOf(
      // 1 flag (mental health) at site A
      OffenderCheckin.create(
        offender = offender1,
        createdBy = practitioner1,
        status = CheckinStatus.SUBMITTED,
        dueDate = checkinStart,
        submittedAt = checkinStart.atTime(OffsetTime.of(12, 11, 0, 0, ZoneOffset.UTC)).toInstant(),
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot" as Object,
          "mentalHealth" to "NOT_GREAT" as Object,
          "assistance" to listOf("NO_HELP") as Object,
          "callback" to "NO" as Object,
        ),
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
      ),

      // 1 flag (callback requested) at site A
      OffenderCheckin.create(
        offender = offender1,
        createdBy = practitioner1,
        status = CheckinStatus.REVIEWED,
        dueDate = checkinStart.plusDays(1),
        submittedAt = checkinStart.plusDays(1).atTime(OffsetTime.of(12, 11, 0, 0, ZoneOffset.UTC)).toInstant(),
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot" as Object,
          "mentalHealth" to "OK" as Object,
          "assistance" to listOf("NO_HELP") as Object,
          "callback" to "YES" as Object,
        ),
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
      ),

      // 1 flag (assistance requested) at site A
      OffenderCheckin.create(
        offender = offender1,
        createdBy = practitioner1,
        status = CheckinStatus.REVIEWED,
        dueDate = checkinStart.plusDays(2),
        submittedAt = checkinStart.plusDays(2).atTime(OffsetTime.of(12, 11, 0, 0, ZoneOffset.UTC)).toInstant(),
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot" as Object,
          "mentalHealth" to "OK" as Object,
          "assistance" to listOf("DRUGS") as Object,
          "moneySupport" to "Need help with drugs" as Object,
          "callback" to "NO" as Object,
        ),
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
      ),

      // no flags at site B
      OffenderCheckin.create(
        offender = offender2,
        createdBy = practitioner2,
        status = CheckinStatus.SUBMITTED,
        dueDate = checkinStart,
        submittedAt = checkinStart.atTime(OffsetTime.of(12, 11, 0, 0, ZoneOffset.UTC)).toInstant(),
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot" as Object,
          "mentalHealth" to "OK" as Object,
          "assistance" to listOf("NO_HELP") as Object,
          "callback" to "NO" as Object,
        ),
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
      ),

      // multiple flags (mental health, assistance, callback requested) at site B
      OffenderCheckin.create(
        offender = offender2,
        createdBy = practitioner2,
        status = CheckinStatus.SUBMITTED,
        dueDate = checkinStart.plusDays(1),
        submittedAt = checkinStart.plusDays(1).atTime(OffsetTime.of(12, 11, 0, 0, ZoneOffset.UTC)).toInstant(),
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot" as Object,
          "mentalHealth" to "STRUGGLING" as Object,
          "assistance" to listOf("MONEY", "HOUSING") as Object,
          "callback" to "YES" as Object,
        ),
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
      ),

      // no flags at site C
      OffenderCheckin.create(
        offender = offender3,
        createdBy = practitioner3,
        status = CheckinStatus.REVIEWED,
        dueDate = checkinStart,
        submittedAt = checkinStart.atTime(OffsetTime.of(12, 11, 0, 0, ZoneOffset.UTC)).toInstant(),
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot" as Object,
          "mentalHealth" to "OK" as Object,
          "assistance" to listOf("NO_HELP") as Object,
          "callback" to "NO" as Object,
        ),
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
      ),
    )

    checkinRepository.saveAll(checkins)

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)

    val flaggedCheckins = stats.flaggedCheckinsPerSite
    assertThat(flaggedCheckins).containsExactlyInAnyOrder(
      SiteCount("Site A", 3),
      SiteCount("Site B", 1),
      SiteCount("Site C", 0),
    )
  }

  @Test
  fun `counts stopped checkins per site`() {
    // practitioners set up at 3 different sites
    val practitioner1 = PRACTITIONER_ALICE.externalUserId()
    val practitioner2 = PRACTITIONER_BOB.externalUserId()
    val practitioner3 = PRACTITIONER_DAVE.externalUserId()
    val siteAssignments = listOf(
      PractitionerSite(practitioner1, "Site A"),
      PractitionerSite(practitioner2, "Site B"),
      PractitionerSite(practitioner3, "Site C"),
    )

    // create offenders assigned to practitioners at each site
    val offenderA1 = offenderRepository.save(
      Offender.create(
        name = "Offender A1",
        crn = "A123456",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_ALICE,
      ),
    )
    val offenderA2 = offenderRepository.save(
      Offender.create(
        name = "Offender A2",
        crn = "A654321",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_ALICE,
      ),
    )
    val offenderB1 = offenderRepository.save(
      Offender.create(
        name = "Offender B1",
        crn = "B123456",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_BOB,
      ),
    )
    val offenderB2 = offenderRepository.save(
      Offender.create(
        name = "Offender B2",
        crn = "B654321",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_BOB,
      ),
    )
    val offenderC1 = offenderRepository.save(
      Offender.create(
        name = "Offender C1",
        crn = "C123456",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_DAVE,
      ),
    )

    // create the deactivation log entries
    offenderEventLogRepository.saveAll(
      listOf(
        OffenderEventLog(
          UUID.randomUUID(),
          LogEntryType.OFFENDER_DEACTIVATED,
          "This is a test reason why checkins have been stopped.",
          practitioner1,
          offenderA1,
        ),
        OffenderEventLog(
          UUID.randomUUID(),
          LogEntryType.OFFENDER_DEACTIVATED,
          "This is a test reason why checkins have been stopped.",
          practitioner1,
          offenderA2,
        ),
        OffenderEventLog(
          UUID.randomUUID(),
          LogEntryType.OFFENDER_DEACTIVATED,
          "This is a test reason why checkins have been stopped.",
          practitioner2,
          offenderB1,
        ),
      ),
    )
    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)
    val stoppedCheckins = stats.stoppedCheckinsPerSite
    assertThat(stoppedCheckins).containsExactlyInAnyOrder(
      SiteCount("Site A", 2),
      SiteCount("Site B", 1),
      SiteCount("Site C", 0),
    )
  }

  @Test
  fun `calculates average flags per check-in for each site`() {
    val practitioner1 = PRACTITIONER_ALICE.externalUserId()
    val practitioner2 = PRACTITIONER_BOB.externalUserId()
    val practitioner3 = PRACTITIONER_DAVE.externalUserId()
    val siteAssignments = listOf(
      PractitionerSite(practitioner1, "Site A"),
      PractitionerSite(practitioner2, "Site B"),
      // PractitionerSite(practitioner3, "Site C"), // we want to make sure "UNKNOWN" (missing mapping) shows up in the results
    )

    val offenderA = offenderRepository.save(
      Offender.create(
        name = "Offender A",
        crn = "A123456",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_ALICE,
      ),
    )
    val offenderB = offenderRepository.save(
      Offender.create(
        name = "Offender B",
        crn = "B123457",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_BOB,
      ),
    )
    val offenderC = offenderRepository.save(
      Offender.create(
        name = "Offender C",
        crn = "C123456",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_DAVE,
      ),
    )

    checkinRepository.saveAll(
      listOf(
        // Check-in with 2 flags
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.SUBMITTED,
          dueDate = LocalDate.now(),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "STRUGGLING",
            "callback" to "YES",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
        ),
        // Check-in with 2 assistance flags
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.SUBMITTED,
          dueDate = LocalDate.now().minusDays(1),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("DRUGS", "HOUSING"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
        ),
        // Check-in with 1 flag
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now().minusDays(2),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.NO_MATCH,
        ),

        OffenderCheckin.create(
          offender = offenderB,
          createdBy = practitioner2,
          status = CheckinStatus.SUBMITTED,
          dueDate = LocalDate.now(),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "NOT_GREAT",
            "callback" to "YES",
            "assistance" to listOf("HOUSING", "MONEY"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.NO_MATCH,
        ),
        OffenderCheckin.create(
          offender = offenderB,
          createdBy = practitioner2,
          status = CheckinStatus.SUBMITTED,
          dueDate = LocalDate.now().minusDays(1),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
        ),
        // some flags, practitioner has no site mapping
        OffenderCheckin.create(
          offender = offenderC,
          createdBy = practitioner3,
          status = CheckinStatus.SUBMITTED,
          dueDate = LocalDate.now().minusDays(3),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "NOT_GREAT",
            "callback" to "YES",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
        ),
      ),
    )
    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)
    val flagAverages = stats.averageFlagsPerCheckinPerSite
    assertThat(flagAverages).hasSize(3)
    assertThat(flagAverages.find { it.location == "Site A" }?.average).isCloseTo(1.67, within(0.01))
    assertThat(flagAverages.find { it.location == "Site B" }?.average).isCloseTo(2.5, within(0.01))
    assertThat(flagAverages.find { it.location == "UNKNOWN" }?.average).isCloseTo(2.0, within(0.01))
    assertThat(stats.averageFlagsPerCheckinTotal).isCloseTo(2.0, within(0.01))
  }

  @Test
  fun `calculates percentage of check-ins with callback request for each site`() {
    val practitioner1 = PRACTITIONER_ALICE.externalUserId()
    val practitioner2 = PRACTITIONER_BOB.externalUserId()
    val practitioner3 = PRACTITIONER_DAVE.externalUserId()
    val siteAssignments = listOf(
      PractitionerSite(practitioner1, "Site A"),
      PractitionerSite(practitioner2, "Site B"),
      // PractitionerSite(practitioner3, "Site C"), // we want to make sure "UNKNOWN" (missing mapping) shows up in the results
    )

    val offenderA = offenderRepository.save(
      Offender.create(
        name = "Offender A",
        crn = "A123456",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_ALICE,
      ),
    )
    val offenderB = offenderRepository.save(
      Offender.create(
        name = "Offender B",
        crn = "B123457",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_BOB,
      ),
    )
    val offenderC = offenderRepository.save(
      Offender.create(
        name = "Offender C",
        crn = "C123456",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_DAVE,
      ),
    )

    checkinRepository.saveAll(
      listOf(
        // Site A: Check-in 1, callback: YES
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.SUBMITTED,
          dueDate = LocalDate.now(),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "STRUGGLING",
            "callback" to "YES",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
        ),
        // Site A: Check-in 2, callback: NO
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.SUBMITTED,
          dueDate = LocalDate.now().minusDays(1),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("DRUGS", "HOUSING"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
        ),
        // Site A: Check-in 3, callback: NO
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now().minusDays(2),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.NO_MATCH,
        ),
        // Site B: Check-in 1, callback: YES
        OffenderCheckin.create(
          offender = offenderB,
          createdBy = practitioner2,
          status = CheckinStatus.SUBMITTED,
          dueDate = LocalDate.now(),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "NOT_GREAT",
            "callback" to "YES",
            "assistance" to listOf("HOUSING", "MONEY"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.NO_MATCH,
        ),
        // Site B: Check-in 2, callback: NO
        OffenderCheckin.create(
          offender = offenderB,
          createdBy = practitioner2,
          status = CheckinStatus.SUBMITTED,
          dueDate = LocalDate.now().minusDays(1),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
        ),
        // UNKNOWN: Check-in 1, callback: YES
        OffenderCheckin.create(
          offender = offenderC,
          createdBy = practitioner3,
          status = CheckinStatus.SUBMITTED,
          dueDate = LocalDate.now().minusDays(3),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "NOT_GREAT",
            "callback" to "YES",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
        ),
      ),
    )
    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)
    val callbackPercentages = stats.callbackRequestPercentagePerSite
    assertThat(callbackPercentages).hasSize(3)
    assertThat(callbackPercentages.find { it.location == "Site A" }?.average).isCloseTo(33.33, within(0.01))
    assertThat(callbackPercentages.find { it.location == "Site B" }?.average).isCloseTo(50.0, within(0.01))
    assertThat(callbackPercentages.find { it.location == "UNKNOWN" }?.average).isCloseTo(100.0, within(0.01))
    assertThat(stats.callbackRequestPercentageTotal).isCloseTo(50.0, within(0.01))
  }

  fun `generic offender notifications stats`() {
    val practitioner1 = PRACTITIONER_ALICE.externalUserId()
    val offenderA = offenderRepository.save(
      Offender.create(
        name = "Offender A",
        crn = "A123456",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_ALICE,
      ),
    )
    val offenderB = offenderRepository.save(
      Offender.create(
        name = "Offender B",
        crn = "B123457",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_ALICE,
      ),
    )

    val notifs = listOf(
      GenericNotification(UUID.randomUUID(), "foo", "ref-1", null, offenderA, "delivered"),
      GenericNotification(UUID.randomUUID(), "foo", "ref-1", null, offenderA, null),
      GenericNotification(UUID.randomUUID(), "bar", "ref-1", null, offenderB, null),
    )
    genericNotificationRepository.saveAll(notifs)

    val stats = perSiteStatsRepository.statsPerSite(listOf(PractitionerSite(practitioner1, "Site A")))

    assertThat(stats.genericNotificationStatusPerSite.size).isEqualTo(3)
    val unknownStatus = stats.genericNotificationStatusPerSite.filter { it.status == "unknown" }
    assertThat(unknownStatus.size).isEqualTo(2)
    assertThat(unknownStatus[0].count).isEqualTo(1)
    assertThat(unknownStatus[1].count).isEqualTo(1)

    val deliveredStatus = stats.genericNotificationStatusPerSite.filter { it.status == "delivered" }
    assertThat(deliveredStatus.size).isEqualTo(1)
    assertThat(deliveredStatus[0].count).isEqualTo(1)
  }

  @Test
  fun `calculates average review response time per site`() {
    val practitioner1 = PRACTITIONER_ALICE.externalUserId()
    val practitioner2 = PRACTITIONER_BOB.externalUserId()
    val practitioner3 = PRACTITIONER_DAVE.externalUserId()
    val siteAssignments = listOf(
      PractitionerSite(practitioner1, "Site A"),
      PractitionerSite(practitioner2, "Site B"),
      // PractitionerSite(practitioner3, "Site C"), // we want to make sure "UNKNOWN" (missing mapping) shows up in the results
    )

    val offenderA = offenderRepository.save(
      Offender.create(
        name = "Offender A",
        crn = "A123456",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_ALICE,
      ),
    )
    val offenderB = offenderRepository.save(
      Offender.create(
        name = "Offender B",
        crn = "B123457",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_BOB,
      ),
    )
    val offenderC = offenderRepository.save(
      Offender.create(
        name = "Offender C",
        crn = "C123456",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_DAVE,
      ),
    )

    val timeNow = java.time.Instant.now()

    checkinRepository.saveAll(
      listOf(
        // Reviewed with 4 hours between submission & review
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.REVIEWED,
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = timeNow.minus(6, ChronoUnit.HOURS),
          reviewedAt = timeNow.minus(2, ChronoUnit.HOURS),
        ),
        // Reviewed with 8 hours between submission & review
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now().minusDays(1),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = timeNow.minus(8, ChronoUnit.HOURS).minus(1, ChronoUnit.DAYS),
          reviewedAt = timeNow.minus(1, ChronoUnit.DAYS),
        ),
        // Submitted not yet reviewed
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.SUBMITTED,
          dueDate = LocalDate.now().minusDays(2),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = timeNow.minus(6, ChronoUnit.HOURS).minus(2, ChronoUnit.DAYS),
        ),
        // Reviewed without reviewedAt field to check null value is handled correctly
        OffenderCheckin.create(
          offender = offenderB,
          createdBy = practitioner2,
          status = CheckinStatus.REVIEWED,
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = timeNow.minus(36, ChronoUnit.HOURS),
        ),
        // Reviewed without submittedAt & reviewedAt fields to check null values are handled correctly
        OffenderCheckin.create(
          offender = offenderB,
          createdBy = practitioner2,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now().minusDays(1),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
        ),
        // Reviewed with 1 hour between submission & review
        OffenderCheckin.create(
          offender = offenderC,
          createdBy = practitioner3,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now().minusDays(2),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = timeNow.minus(3, ChronoUnit.HOURS),
          reviewedAt = timeNow.minus(2, ChronoUnit.HOURS),
        ),
        // Reviewed with 30 minutes & 45 seconds between submission & review
        OffenderCheckin.create(
          offender = offenderC,
          createdBy = practitioner3,
          status = CheckinStatus.REVIEWED,
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = timeNow.minus(30, ChronoUnit.MINUTES).minus(45, ChronoUnit.SECONDS),
          reviewedAt = timeNow,
        ),
        // Created not submitted or reviewed
        OffenderCheckin.create(
          offender = offenderC,
          createdBy = practitioner3,
          dueDate = LocalDate.now().minusDays(1),
          status = CheckinStatus.CREATED,
        ),
      ),
    )

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)
    val reviewResponseTimeAverages = stats.averageReviewTimePerCheckinPerSite
    assertThat(reviewResponseTimeAverages).hasSize(3)
    assertThat(reviewResponseTimeAverages.find { it.location == "Site A" }?.averageTimeText).isEqualTo("6h0m0s")
    assertThat(reviewResponseTimeAverages.find { it.location == "Site B" }?.averageTimeText).isEqualTo("0h0m0s")
    assertThat(reviewResponseTimeAverages.find { it.location == "UNKNOWN" }?.averageTimeText).isEqualTo("0h45m23s")
    assertThat(stats.averageReviewTimePerCheckinTotal).isEqualTo("3h22m41s")
  }

  @Test
  fun `calculates average time to register per site`() {
    val practitioner1 = PRACTITIONER_ALICE.externalUserId()
    val practitioner2 = PRACTITIONER_BOB.externalUserId()
    val practitioner3 = PRACTITIONER_DAVE.externalUserId()
    val siteAssignments = listOf(
      PractitionerSite(practitioner1, "Site A"),
      PractitionerSite(practitioner2, "Site B"),
    )

    val offenderA1 = offenderRepository.save(Offender.create(name = "Offender A1", crn = "A123456", firstCheckinDate = LocalDate.now(), practitioner = PRACTITIONER_ALICE))
    val offenderA2 = offenderRepository.save(Offender.create(name = "Offender A2", crn = "A234567", firstCheckinDate = LocalDate.now(), practitioner = PRACTITIONER_ALICE))
    val offenderB1 = offenderRepository.save(Offender.create(name = "Offender B1", crn = "B123456", firstCheckinDate = LocalDate.now(), practitioner = PRACTITIONER_BOB))
    val offenderC1 = offenderRepository.save(Offender.create(name = "Offender C1", crn = "C123456", firstCheckinDate = LocalDate.now(), practitioner = PRACTITIONER_DAVE))

    val now = Instant.now()

    val setupA1 = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offenderA1,
      practitioner = practitioner1,
      createdAt = now.plusSeconds(100),
      startedAt = now,
    )
    val setupA2 = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offenderA2,
      practitioner = practitioner1,
      createdAt = now.plusSeconds(300),
      startedAt = now.plusSeconds(100),
    )
    val setupB1 = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offenderB1,
      practitioner = practitioner2,
      createdAt = now.plusSeconds(50),
      startedAt = now,
    )
    val setupC1 = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offenderC1,
      practitioner = practitioner3,
      createdAt = now.plusSeconds(1000),
      startedAt = now,
    )
    offenderSetupRepository.saveAll(listOf(setupA1, setupA2, setupB1, setupC1))
    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)

    val averages = stats.averageTimeToRegisterPerSite
    assertThat(averages).hasSize(3)
    assertThat(averages.find { it.location == "Site A" }?.averageTimeText).isEqualTo("0h2m30s")
    assertThat(averages.find { it.location == "Site B" }?.averageTimeText).isEqualTo("0h0m50s")
    assertThat(averages.find { it.location == "UNKNOWN" }?.averageTimeText).isEqualTo("0h16m40s")
    assertThat(stats.averageTimeToRegisterTotal).isEqualTo("0h5m37s")
  }

  @Test
  fun `calculates average time to complete checkin per site`() {
    val practitioner1 = PRACTITIONER_ALICE.externalUserId()
    val practitioner2 = PRACTITIONER_BOB.externalUserId()
    val practitioner3 = PRACTITIONER_DAVE.externalUserId()
    val siteAssignments = listOf(
      PractitionerSite(practitioner1, "Site A"),
      PractitionerSite(practitioner2, "Site B"),
    )

    val offenderA = offenderRepository.save(Offender.create(name = "Offender A1", crn = "A123456", firstCheckinDate = LocalDate.now(), practitioner = PRACTITIONER_ALICE))
    val offenderB = offenderRepository.save(Offender.create(name = "Offender B1", crn = "B123456", firstCheckinDate = LocalDate.now(), practitioner = PRACTITIONER_BOB))
    val offenderC = offenderRepository.save(Offender.create(name = "Offender C1", crn = "C123456", firstCheckinDate = LocalDate.now(), practitioner = PRACTITIONER_DAVE))

    val now = Instant.now()

    checkinRepository.saveAll(
      listOf(
        // Completed in 100 seconds
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.SUBMITTED,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = now.minusSeconds(100),
          checkinStartedAt = now.minusSeconds(200),
        ),
        // Completed in 30 seconds
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now().minusDays(1),
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = now,
          checkinStartedAt = now.minusSeconds(30),
        ),
        // Completed in 45 seconds
        OffenderCheckin.create(
          offender = offenderB,
          createdBy = practitioner2,
          status = CheckinStatus.SUBMITTED,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = now,
          checkinStartedAt = now.minusSeconds(45),
        ),
        // Completed in 135 seconds
        OffenderCheckin.create(
          offender = offenderB,
          createdBy = practitioner2,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now().minusDays(1),
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = now,
          checkinStartedAt = now.minusSeconds(135),
        ),
        // Completed in 20 seconds
        OffenderCheckin.create(
          offender = offenderC,
          createdBy = practitioner3,
          status = CheckinStatus.REVIEWED,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = now,
          checkinStartedAt = now.minusSeconds(20),
        ),
        // Completed in 20 seconds
        OffenderCheckin.create(
          offender = offenderC,
          createdBy = practitioner3,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now().minusDays(1),
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = now,
          checkinStartedAt = now.minusSeconds(20),
        ),
        // Completed in 60 seconds
        OffenderCheckin.create(
          offender = offenderC,
          createdBy = practitioner3,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now().minusDays(2),
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          submittedAt = now,
          checkinStartedAt = now.minusSeconds(60),
        ),
        // Created not submitted or reviewed
        OffenderCheckin.create(
          offender = offenderC,
          createdBy = practitioner3,
          dueDate = LocalDate.now().plusDays(1),
          status = CheckinStatus.CREATED,
        ),
      ),
    )

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)
    val averages = stats.averageCheckinCompletionTimePerSite
    assertThat(averages).hasSize(3)
    assertThat(averages.find { it.location == "Site A" }?.averageTimeText).isEqualTo("0h1m5s")
    assertThat(averages.find { it.location == "Site B" }?.averageTimeText).isEqualTo("0h1m30s")
    assertThat(averages.find { it.location == "UNKNOWN" }?.averageTimeText).isEqualTo("0h0m33s")
    assertThat(stats.averageCheckinCompletionTimeTotal).isEqualTo("0h0m58s")
  }

  @Test
  fun `calculates average time taken to complete a review of a checkin per site`() {
    val practitioner1 = PRACTITIONER_ALICE.externalUserId()
    val practitioner2 = PRACTITIONER_BOB.externalUserId()
    val practitioner3 = PRACTITIONER_DAVE.externalUserId()
    val siteAssignments = listOf(
      PractitionerSite(practitioner1, "Site A"),
      PractitionerSite(practitioner2, "Site B"),
    )

    val offenderA = offenderRepository.save(
      Offender.create(
        name = "Offender A",
        crn = "A123456",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_ALICE,
      ),
    )
    val offenderB = offenderRepository.save(
      Offender.create(
        name = "Offender B",
        crn = "B123457",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_BOB,
      ),
    )
    val offenderC = offenderRepository.save(
      Offender.create(
        name = "Offender C",
        crn = "C123456",
        firstCheckinDate = LocalDate.now(),
        practitioner = PRACTITIONER_DAVE,
      ),
    )

    val now = Instant.now()
    checkinRepository.saveAll(
      listOf(
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now(),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "STRUGGLING",
            "callback" to "YES",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          reviewStartedAt = now,
          reviewedAt = now.plusSeconds(100),
        ),
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now().minusDays(1),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("DRUGS", "HOUSING"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          reviewStartedAt = now.plusSeconds(50),
          reviewedAt = now.plusSeconds(250),
        ),
        OffenderCheckin.create(
          offender = offenderA,
          createdBy = practitioner1,
          status = CheckinStatus.SUBMITTED,
          dueDate = LocalDate.now().minusDays(2),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.NO_MATCH,
          reviewStartedAt = now,
        ),
        OffenderCheckin.create(
          offender = offenderB,
          createdBy = practitioner2,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now(),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "NOT_GREAT",
            "callback" to "YES",
            "assistance" to listOf("HOUSING", "MONEY"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.NO_MATCH,
          reviewStartedAt = now,
          reviewedAt = now.plusSeconds(50),
        ),
        OffenderCheckin.create(
          offender = offenderB,
          createdBy = practitioner2,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now().minusDays(1),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "OK",
            "callback" to "NO",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          reviewStartedAt = now.plusSeconds(100),
          reviewedAt = now,
        ),
        OffenderCheckin.create(
          offender = offenderC,
          createdBy = practitioner3,
          status = CheckinStatus.REVIEWED,
          dueDate = LocalDate.now().minusDays(3),
          surveyResponse = mapOf(
            "version" to "2025-07-10@pilot",
            "mentalHealth" to "NOT_GREAT",
            "callback" to "YES",
            "assistance" to listOf("NO_HELP"),
          ) as Map<String, Object>,
          autoIdCheck = AutomatedIdVerificationResult.MATCH,
          reviewStartedAt = now,
          reviewedAt = now.plusSeconds(1000),
        ),
      ),
    )

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)
    val averages = stats.averageTimeTakenToCompleteCheckinReviewPerSite
    assertThat(averages).hasSize(3)

    assertThat(averages.find { it.location == "Site A" }?.averageTimeText).isEqualTo("0h2m30s")
    assertThat(averages.find { it.location == "Site B" }?.averageTimeText).isEqualTo("0h0m50s")
    assertThat(averages.find { it.location == "UNKNOWN" }?.averageTimeText).isEqualTo("0h16m40s")
    assertThat(stats.averageTimeTakenToCompleteCheckinReviewTotal).isEqualTo("0h5m37s")
  }

  @Test
  fun `device type stats`() {
    val practitioner1 = PRACTITIONER_ALICE.externalUserId()
    val practitioner2 = PRACTITIONER_BOB.externalUserId()
    val siteAssignments = listOf(
      PractitionerSite(practitioner1, "Site A"),
      PractitionerSite(practitioner2, "Site B"),
    )
    val offenderA = offenderRepository.save(Offender.create(name = "Offender A1", crn = "A123456", firstCheckinDate = LocalDate.now(), practitioner = PRACTITIONER_ALICE))
    val offenderB = offenderRepository.save(Offender.create(name = "Offender B1", crn = "B123456", firstCheckinDate = LocalDate.now(), practitioner = PRACTITIONER_BOB))
    val now = Instant.now()
    val today = ZonedDateTime.ofInstant(now, ZoneId.of("Europe/London")).toLocalDate()

    val protoCheckin = CheckinParam(
      offender = offenderA,
      createdBy = practitioner1,
      status = CheckinStatus.SUBMITTED,
      autoIdCheck = AutomatedIdVerificationResult.MATCH,
      submittedAt = now.minusSeconds(100),
      checkinStartedAt = now.minusSeconds(200),
    )
    val deviceMobile = mapOf("deviceType" to "Mobile") as Map<String, Object>
    val deviceTablet = mapOf("deviceType" to "Tablet") as Map<String, Object>

    val mobilesA = 10L.downTo(1L).map { protoCheckin.copy(dueDate = today.minusDays(it), device = deviceMobile) }
    val tabletsA = 15L.downTo(11L).map { protoCheckin.copy(dueDate = today.minusDays(it), device = deviceTablet) }

    val mobilesB = 5L.downTo(1L).map { protoCheckin.copy(dueDate = today.minusDays(it), device = deviceMobile, offender = offenderB) }
    val tabletsB = 10L.downTo(6L).map { protoCheckin.copy(dueDate = today.minusDays(it), device = deviceTablet, offender = offenderB) }

    checkinRepository.saveAll(createCheckins(mobilesA + tabletsA + mobilesB + tabletsB))

    val stats = perSiteStatsRepository.statsPerSite(siteAssignments)
    assertThat(stats.deviceType.size == 4)
    assertThat(stats.deviceType).isEqualTo(
      listOf(
        LabeledSiteCount("Site A", label = "Mobile", count = 10, total = 15, 60.0),
        LabeledSiteCount("Site A", label = "Tablet", count = 5, total = 10, 40.0),
        LabeledSiteCount("Site B", label = "Mobile", count = 5, total = 15, 60.0),
        LabeledSiteCount("Site B", label = "Tablet", count = 5, total = 10, 40.0),
      ),
    )
  }
}
