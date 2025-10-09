package uk.gov.justice.digital.hmpps.esupervisionapi.integration.stats

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.PRACTITIONER_ALICE
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.create
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinNotification
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerSite
import uk.gov.justice.digital.hmpps.esupervisionapi.stats.PerSiteStatsRepository
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID

class PerSiteStatsRepositoryTest : IntegrationTestBase() {

  @Autowired lateinit var perSiteStatsRepository: PerSiteStatsRepository

  @BeforeEach
  fun setup() {
    checkinNotificationRepository.deleteAll()
    checkinRepository.deleteAll()
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
      uk.gov.justice.digital.hmpps.esupervisionapi.stats.SiteCount("Site A", 2),
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
      uk.gov.justice.digital.hmpps.esupervisionapi.stats.SiteCount("Site A", 1),
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
}
