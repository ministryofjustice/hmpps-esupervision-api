package uk.gov.justice.digital.hmpps.esupervisionapi.integration.jobs

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.kotlin.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.MockS3Config
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.PRACTITIONER_ALICE
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.create
import uk.gov.justice.digital.hmpps.esupervisionapi.jobs.OffenderCheckinExpiryJob
import uk.gov.justice.digital.hmpps.esupervisionapi.jobs.cutoffDate
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Import(MockS3Config::class)
class OffenderCheckinExpiryJobTest : IntegrationTestBase() {

  @Autowired lateinit var job: OffenderCheckinExpiryJob

  @Autowired lateinit var notificationService: NotificationService

  @AfterEach
  fun cleanUp() {
    offenderEventLogRepository.deleteAll()
    offenderSetupRepository.deleteAll()
    checkinRepository.deleteAll()
    offenderRepository.deleteAll()
  }

  @Test
  fun `job marks overdue checkin as expired`() {
    // Reset mocks to a clean state
    reset(notificationService)

    val today = LocalDate.now()
    val practitioner = PRACTITIONER_ALICE

    val offender = Offender.create(
      name = "Bob Smith",
      crn = "B090901",
      dateOfBirth = LocalDate.of(1980, 1, 1),
      firstCheckinDate = today,
      checkinInterval = CheckinInterval.WEEKLY,
      practitioner = practitioner,
      status = OffenderStatus.VERIFIED,
    )
    offenderRepository.save(offender)

    val checkinUuid = UUID.randomUUID()
    val checkin1 = OffenderCheckin.create(
      uuid = checkinUuid,
      offender = offender,
      createdAt = Instant.now(),
      createdBy = practitioner.externalUserId(),
      status = CheckinStatus.CREATED,
      dueDate = today.minusDays(3),
    )
    val checkin2 = checkin1.variant(newDueDate = today.minusDays(2))
    val checkin3 = checkin1.variant(newDueDate = today.minusDays(1))
    val checkin4 = checkin1.variant(newDueDate = today.minusDays(4))

    checkinRepository.saveAll(listOf(checkin1, checkin2, checkin3, checkin4))

    job.process()

    val updated1 = checkinRepository.findByUuid(checkin1.uuid).orElseThrow()
    Assertions.assertEquals(CheckinStatus.EXPIRED, updated1.status)
    val updated2 = checkinRepository.findByUuid(checkin2.uuid).orElseThrow()
    Assertions.assertEquals(CheckinStatus.CREATED, updated2.status)
    val updated3 = checkinRepository.findByUuid(checkin3.uuid).orElseThrow()
    Assertions.assertEquals(CheckinStatus.CREATED, updated3.status)
    val updated4 = checkinRepository.findByUuid(checkin4.uuid).orElseThrow()
    Assertions.assertEquals(CheckinStatus.EXPIRED, updated4.status)
  }

  @Test
  fun `cutoff date calculation`() {
    val clock = Clock.fixed(Instant.parse("2025-09-13T14:30:00Z"), ZoneId.systemDefault())
    val checkinWindow = Duration.ofHours(72)
    val actual = cutoffDate(clock, checkinWindow)

    Assertions.assertEquals(LocalDate.of(2025, 9, 10), actual)
  }
}

private fun OffenderCheckin.variant(newDueDate: LocalDate): OffenderCheckin = OffenderCheckin.create(
  uuid = UUID.randomUUID(),
  offender = this.offender,
  createdAt = this.createdAt,
  createdBy = this.createdBy,
  status = this.status,
  dueDate = newDueDate,
)
