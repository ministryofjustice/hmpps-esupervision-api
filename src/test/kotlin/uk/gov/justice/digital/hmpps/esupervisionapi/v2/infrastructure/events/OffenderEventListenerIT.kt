package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.annotation.DirtiesContext
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderDeactivatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SetupInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityCheckOutcome
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityChecker
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.OffenderEligibilitySyncJob
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationService
import java.time.Clock
import java.time.LocalDate
import java.util.UUID.randomUUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OffenderEventListenerIT : IntegrationTestBase() {

  @Autowired
  private lateinit var notificationService: NotificationService

  @Autowired
  @Qualifier("offenderEventExecutor")
  private lateinit var offenderEventExecutor: ThreadPoolTaskExecutor

  @Autowired
  private lateinit var offenderDeactivationService: OffenderDeactivationService

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var offenderPersistenceService: OffenderPersistenceService

  @Autowired
  private lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired private lateinit var offenderSetupRepository: OffenderSetupRepository

  @Autowired private lateinit var clock: Clock

  @AfterEach
  fun cleanUp() {
    reset(notificationService)
    offenderSetupRepository.deleteAll()
    outboxItemRepository.deleteAll()
    offenderRepository.deleteAll()
    offenderRepository.flush()
  }

  @Test
  fun `processEvent - offender deactivated - mark outbox item as sent - success`() {
    var offender = offenderTemplate.copy(firstCheckin = LocalDate.now(), status = OffenderStatus.VERIFIED).toEntity()
    offender = offenderRepository.save(offender)
    val setup = OffenderSetup(randomUUID(), offender, offender.practitionerId, clock.instant())
    offenderSetupRepository.save(setup)

    offender.status = OffenderStatus.INACTIVE
    val event = OffenderDeactivatedEvent(
      offenderId = offender.id,
      offender = offender.dto(),
      auditEventType = OffenderAuditEventType.OFFENDER_DEACTIVATED,
      setup = SetupInfo.from(setup),
      activeEventNumber = null,
    )
    offenderPersistenceService.offenderDeactivation(offender, event)

    await().atMost(5, TimeUnit.SECONDS).untilAsserted {
      val outboxItem = outboxItemRepository.findByTypeAndEntityId(OutboxItemType.OFFENDER_DEACTIVATED, event.setup!!.primaryKey).orElseThrow()
      assertEquals(OutboxItemStatus.SENT, outboxItem.status)
    }
    verify(notificationService).sendDeactivationCompletedNotifications(any())
  }

  @Test
  @DirtiesContext
  fun `batch waits for the committed deactivation listener before recording completion`() {
    val offender = offenderRepository.save(
      offenderTemplate.copy(firstCheckin = LocalDate.now(), status = OffenderStatus.VERIFIED).toEntity(),
    )
    val setup = offenderSetupRepository.save(OffenderSetup(randomUUID(), offender, offender.practitionerId, clock.instant()))
    val details = ContactDetails(
      crn = offender.crn,
      name = Name("John", "Doe"),
      events = emptyList(),
      contactSuspended = false,
      dateOfBirth = LocalDate.of(1980, 1, 1),
    )
    val ndelius: INdiliusApiClient = mock {
      on { getContactDetailsForMultiple(any()) }.thenReturn(listOf(details))
    }
    val eligibilityChecker: EligibilityChecker = mock {
      on { check(any(), any(), any()) }
        .thenReturn(EligibilityResult(EligibilityCheckOutcome.INELIGIBLE, "Ineligible", "RULE"))
    }
    val appConfig: AppConfig = mock {
      on { enabledFeatures }.thenReturn(setOf(Feature.ESUP_2082))
    }
    val jobLogs: JobLogRepository = mock {
      on { saveAndFlush(any<JobLog>()) } doAnswer { it.arguments[0] as JobLog }
    }
    val listenerStarted = CountDownLatch(1)
    val finishListener = CountDownLatch(1)
    doAnswer {
      listenerStarted.countDown()
      check(finishListener.await(10, TimeUnit.SECONDS))
      null
    }.whenever(notificationService).sendDeactivationCompletedNotifications(any())
    val job = OffenderEligibilitySyncJob(
      clock = clock,
      jobLogRepository = jobLogs,
      offenderRepository = offenderRepository,
      offenderSetupRepository = offenderSetupRepository,
      ndiliusApiClient = ndelius,
      eligibilityChecker = eligibilityChecker,
      offenderDeactivationService = offenderDeactivationService,
      chunkSize = 100,
      appConfig = appConfig,
      offenderEventExecutor = offenderEventExecutor,
      batchEnabled = true,
    )
    val runner = Executors.newSingleThreadExecutor()
    try {
      val result = runner.submit { job.process() }
      assertThat(listenerStarted.await(5, TimeUnit.SECONDS)).isTrue()
      await().atMost(5, TimeUnit.SECONDS).until { offenderEventExecutor.threadPoolExecutor.isShutdown }
      assertThat(result.isDone).isFalse()
      verify(jobLogs).saveAndFlush(any<JobLog>())
      assertThat(offenderRepository.findById(offender.id).orElseThrow().status).isEqualTo(OffenderStatus.INACTIVE)
      assertThat(
        outboxItemRepository.findByTypeAndEntityId(OutboxItemType.OFFENDER_DEACTIVATED, setup.id).orElseThrow().status,
      ).isEqualTo(OutboxItemStatus.INITIAL)

      finishListener.countDown()
      result.get(5, TimeUnit.SECONDS)

      verify(jobLogs, times(2)).saveAndFlush(any<JobLog>())
      verify(notificationService).sendDeactivationCompletedNotifications(any())
      assertThat(
        outboxItemRepository.findByTypeAndEntityId(OutboxItemType.OFFENDER_DEACTIVATED, setup.id).orElseThrow().status,
      ).isEqualTo(OutboxItemStatus.SENT)
    } finally {
      finishListener.countDown()
      runner.shutdownNow()
      check(runner.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  @TestConfiguration
  class TestConfig {
    @Bean
    @Primary
    fun notificationV2Service(): NotificationService = mock()
  }
}
