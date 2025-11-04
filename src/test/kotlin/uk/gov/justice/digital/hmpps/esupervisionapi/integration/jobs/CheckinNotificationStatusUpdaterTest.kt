package uk.gov.justice.digital.hmpps.esupervisionapi.integration.jobs

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.MockS3Config
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.PRACTITIONER_ALICE
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.create
import uk.gov.justice.digital.hmpps.esupervisionapi.jobs.CheckinNotificationStatusUpdater
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.GenericNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.SingleNotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.StatusCollection
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinNotification
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinRepository
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@Import(MockS3Config::class)
class CheckinNotificationStatusUpdaterTest : IntegrationTestBase() {

  @Autowired lateinit var offenderCheckinRepository: OffenderCheckinRepository

  @Autowired private lateinit var notificationService: NotificationService

  @Autowired private lateinit var statusUpdater: CheckinNotificationStatusUpdater

  @Autowired lateinit var genericNotificationRepository: GenericNotificationRepository

  private val clock: Clock = Clock.systemUTC()

  @AfterEach
  fun cleanUp() {
    genericNotificationRepository.deleteAll()
    offenderEventLogRepository.deleteAll()
    offenderSetupRepository.deleteAll()
    offenderCheckinRepository.deleteAll()
    offenderRepository.deleteAll()
  }

  @Test
  fun `verify that one-off notifications are processed`() {
    reset(notificationService)
    whenever(notificationService.notificationStatus(any(), anyOrNull()))
      .thenReturn(StatusCollection(listOf(), null))

    val instant = clock.instant()
    val today = LocalDate.now(clock)
    val practitioner = PRACTITIONER_ALICE
    val offender = Offender.create("Bob Smith", "B090901", LocalDate.of(1980, 1, 1), today, CheckinInterval.WEEKLY, practitioner = practitioner)
    offenderRepository.save(offender)

    val checkin = OffenderCheckin.create(
      uuid = UUID.randomUUID(),
      offender = offender,
      createdBy = practitioner.externalUserId(),
    )
    checkinRepository.save(checkin)
    val singleNotifContext = SingleNotificationContext.forCheckin(today)
    val checkinNotification = CheckinNotification(
      notificationId = UUID.randomUUID(),
      checkin = checkin.uuid,
      reference = singleNotifContext.reference,
      job = null,
      status = null,
      createdAt = instant.minusSeconds(50),
    )
    checkinNotificationRepository.save(checkinNotification)

    statusUpdater.process()

    verify(notificationService, times(6)).notificationStatus(any(), anyOrNull())
  }
}
