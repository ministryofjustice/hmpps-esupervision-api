package uk.gov.justice.digital.hmpps.esupervisionapi.integration.jobs

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.MockS3Config
import uk.gov.justice.digital.hmpps.esupervisionapi.jobs.GenericNotificationStatusUpdater
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.GenericNotification
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.GenericNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationStatusCollection
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import java.util.UUID

@Import(MockS3Config::class)
class GenericNotificationStatusUpdaterTest : IntegrationTestBase() {

  @Autowired lateinit var updater: GenericNotificationStatusUpdater

  @Autowired lateinit var notificationService: NotificationService

  @Autowired lateinit var genericNotificationRepository: GenericNotificationRepository

  @AfterEach
  fun cleanup() {
    genericNotificationRepository.deleteAll()
    reset(notificationService)
  }

  @Test
  fun `process updates statuses for recent notifications`() {
    val reference = "bulk-ref-123"
    val id1 = UUID.randomUUID()
    val id2 = UUID.randomUUID()
    val idOther = UUID.randomUUID() // a notification for same reference but not present in DB

    val n1 = GenericNotification(
      notificationId = id1,
      messageType = NotificationType.PractitionerCheckinMissed.name,
      reference = reference,
    )
    val n2 = GenericNotification(
      notificationId = id2,
      messageType = NotificationType.PractitionerCheckinMissed.name,
      reference = reference,
    )

    // notif. with terminal status -> will be skipped by the job
    val nTerminal = GenericNotification(
      notificationId = UUID.randomUUID(),
      messageType = NotificationType.PractitionerCheckinMissed.name,
      reference = reference,
      status = "delivered",
    )

    // ignored notif. type -> will be skipped by the job
    val nIgnoredType = GenericNotification(
      notificationId = UUID.randomUUID(),
      messageType = NotificationType.OffenderCheckinInvite.name,
      reference = reference,
    )

    genericNotificationRepository.saveAll(listOf(n1, n2, nTerminal, nIgnoredType))

    reset(notificationService)

    val page1 = TestStatusCollection(
      notifications = listOf(
        NotificationInfo(id1, "delivered"),
        NotificationInfo(idOther, "technical-failure"), // not present in DB -> should be ignored
      ),
      hasNextPage = true,
      previousPageParam = "page-1-token",
    )
    val page2 = TestStatusCollection(
      notifications = listOf(
        NotificationInfo(id2, "temporary-failure"),
      ),
      hasNextPage = false,
      previousPageParam = null,
    )

    whenever(notificationService.notificationStatus(any(), anyOrNull())).thenReturn(page1, page2)

    updater.process()

    val all = genericNotificationRepository.findAll()
    val statusesById = all.associateBy({ it.notificationId }, { it.status })

    assertEquals("delivered", statusesById[id1])
    assertEquals("temporary-failure", statusesById[id2])

    assertEquals("delivered", statusesById[nTerminal.notificationId])
    assertEquals(null, statusesById[nIgnoredType.notificationId])
  }
}

private data class TestStatusCollection(
  override val notifications: List<NotificationInfo>,
  override val hasNextPage: Boolean,
  override val previousPageParam: String?,
) : NotificationStatusCollection
