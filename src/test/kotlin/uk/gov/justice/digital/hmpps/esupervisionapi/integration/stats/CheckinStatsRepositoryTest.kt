package uk.gov.justice.digital.hmpps.esupervisionapi.integration.stats

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.PRACTITIONER_ALICE
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.create
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinNotification
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerSite
import uk.gov.justice.digital.hmpps.esupervisionapi.stats.PerSiteStatsRepository
import java.time.LocalDate
import java.util.UUID

class CheckinStatsRepositoryTest : IntegrationTestBase() {

  @Autowired lateinit var perSiteStatsRepository: PerSiteStatsRepository

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

    val counts = perSiteStatsRepository.checkinsSentPerSite(siteAssignments)

    assertThat(counts).containsExactlyInAnyOrder(
      uk.gov.justice.digital.hmpps.esupervisionapi.stats.SiteCount("Site A", 2),
    )
  }
}
