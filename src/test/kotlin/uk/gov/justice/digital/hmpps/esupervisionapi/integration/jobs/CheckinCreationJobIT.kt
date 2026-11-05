package uk.gov.justice.digital.hmpps.esupervisionapi.integration.jobs

import jakarta.persistence.EntityManager
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinLinkStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PartialCheckinCreatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.CheckinCreationJob
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotifyGatewayService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationService
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.Period
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@TestPropertySource(properties = ["app.scheduling.v2-checkin-creation.chunk-size=1"])
class CheckinCreationJobIT : IntegrationTestBase() {

  @Value("\${app.scheduling.v2-checkin-creation.chunk-size}")
  var chunkSize: Int = 0

  @Autowired
  lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  lateinit var clock: Clock

  @Autowired
  lateinit var offenderRepository: OffenderRepository

  @Autowired
  lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired
  lateinit var checkinCreationService: CheckinCreationService

  @Autowired
  lateinit var offenderDeactivationService: OffenderDeactivationService

  @Autowired
  lateinit var jobLogRepository: JobLogRepository

  @Autowired
  lateinit var entityManager: EntityManager

  @Autowired
  lateinit var transactionTemplate: TransactionTemplate

  @Autowired
  lateinit var job: CheckinCreationJob

  @Autowired
  lateinit var appConfig: AppConfig

  @Autowired
  lateinit var deliusEventRepository: CheckinDeliusEventRepository

  @MockitoBean
  lateinit var ndeliusApiClient: INdiliusApiClient

  @MockitoBean
  lateinit var domainEventPublisher: DomainEventPublisher

  @MockitoBean
  lateinit var notifyGateway: NotifyGatewayService

  @BeforeEach
  fun setup() {
    offenderTemplate.copy(
      crn = "A000001",
      uuid = UUID.randomUUID(),
      firstCheckin = clock.today(),
      status = OffenderStatus.VERIFIED,
    )
      .toEntity().let { offenderRepository.save(it) }
    offenderTemplate.copy(
      crn = "A000002",
      uuid = UUID.randomUUID(),
      firstCheckin = clock.today(),
      status = OffenderStatus.VERIFIED,
    )
      .toEntity().let { offenderRepository.save(it) }

    whenever(ndeliusApiClient.getContactDetailsForMultiple(any()))
      .thenAnswer { contactDetailsFor(it.getArgument(0)) }

    whenever(notifyGateway.send(any(), any(), any(), any(), any()))
      .thenAnswer { UUID.randomUUID() }
  }

  // runs the check-in event listeners (invites)
  @Autowired
  @Qualifier("taskExecutor")
  lateinit var taskExecutor: ThreadPoolTaskExecutor

  @AfterEach
  fun cleanDb() {
    // Committed check-ins start listeners in the background; let them finish before cleaning up,
    // or they write to the tables after they're truncated.
    await().atMost(Duration.ofSeconds(30)).until { taskExecutor.activeCount == 0 && taskExecutor.threadPoolExecutor.queue.isEmpty() }
    jdbcTemplate.update("TRUNCATE TABLE checkin_delius_event_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE generic_notification_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_checkin_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE event_audit_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE outbox_items RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_v2 RESTART IDENTITY CASCADE")

    reset(ndeliusApiClient, domainEventPublisher, notifyGateway)
  }

  @Test
  fun `run checkin creation job`() {
    job.process()

    await()
      .atMost(Duration.ofSeconds(30))
      .pollInterval(100, TimeUnit.MILLISECONDS)
      .untilAsserted {
        val checkins = checkinRepository.findAll().associateBy { it.offender.crn }
        assertEquals(2, checkins.size)

        outboxItemRepository
          .findByTypeAndEntityId(OutboxItemType.CHECKIN_CREATED, checkins["A000001"]!!.id)
          .orElseThrow()
          .let { assertEquals(OutboxItemStatus.SENT, it.status) }

        outboxItemRepository
          .findByTypeAndEntityId(OutboxItemType.CHECKIN_CREATED, checkins["A000002"]!!.id)
          .orElseThrow()
          .let { assertEquals(OutboxItemStatus.SENT, it.status) }
      }
  }

  @Test
  fun `no check-in is created for an offender whose check-ins were stopped after the job read them`() {
    val offender = offenderRepository.findByCrn("A000001").orElseThrow()
    val checkin = prepareCheckin(offender)
    // check-ins stopped while the job was getting contact details
    jdbcTemplate.update("update offender_v2 set status = 'INACTIVE' where id = ?", offender.id)

    val created = checkinCreationService.createCheckins(listOf(checkin))

    assertEquals(0, created.size)
    assertEquals(0, checkinRepository.count())
  }

  @Test
  fun `stopping check-ins while a check-in is being created cancels it`() {
    val offender = offenderRepository.findByCrn("A000001").orElseThrow()
    val checkin = prepareCheckin(offender)

    lateinit var stopping: CompletableFuture<Boolean>
    transactionTemplate.executeWithoutResult {
      assertEquals(1, checkinCreationService.createCheckins(listOf(checkin)).size)
      stopping = CompletableFuture.supplyAsync {
        offenderDeactivationService.deactivateOffender(offender, "stopped", practitionerMessageFragment = null)
      }
      // stopping waits for the creation to commit
      await().atMost(Duration.ofSeconds(10)).until {
        jdbcTemplate.queryForObject("select count(*) from pg_locks where not granted", Int::class.java)!! > 0
      }
    }

    assertEquals(true, stopping.get(10, TimeUnit.SECONDS))
    assertEquals(CheckinStatus.CANCELLED, checkinRepository.findByUuid(checkin.first.uuid).orElseThrow().status)
  }

  private fun prepareCheckin(offender: Offender): Pair<OffenderCheckin, PartialCheckinCreatedEvent> {
    val checkin = checkinCreationService.prepareCheckinForOffender(offender, clock.today())
    val contactDetails = ndeliusApiClient.getContactDetailsForMultiple(listOf(offender.crn)).single()
    return checkin to PartialCheckinCreatedEvent(
      offenderId = offender.id,
      practitionerId = "SYSTEM",
      checkin = checkin.dto(contactDetails, clock = clock, checkinWindow = Period.ofDays(3)),
      offenderContactPreference = offender.contactPreference,
      currentEvent = 1L,
      checkinMode = offender.mode,
    )
  }

  @Test
  fun `activates a scheduled check-in instead of creating another`() {
    val offender = offenderRepository.findByCrn("A000001").orElseThrow()
    val scheduled = checkinRepository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.SCHEDULED,
        dueDate = clock.today(),
        createdAt = clock.instant(),
        createdBy = "SYSTEM",
      ),
    )

    job.process()

    val checkins = checkinRepository.findAll()
    assertEquals(2, checkins.size)
    val activated = checkins.single { it.id == scheduled.id }
    assertEquals(scheduled.uuid, activated.uuid)
    assertEquals(CheckinStatus.CREATED, activated.status)
    await()
      .atMost(Duration.ofSeconds(10))
      .pollInterval(100, TimeUnit.MILLISECONDS)
      .untilAsserted {
        outboxItemRepository
          .findByTypeAndEntityId(OutboxItemType.CHECKIN_CREATED, scheduled.id)
          .orElseThrow()
          .let { assertEquals(OutboxItemStatus.SENT, it.status) }
      }
  }

  @Test
  fun `with ESUP_2223 on, a check-in that wasn't scheduled is created and recorded as scheduled`() {
    val featuresBefore = appConfig.enabledFeatures
    ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore + Feature.ESUP_2223)
    val checkins = try {
      job.process()
      // the invites are sent asynchronously and record link events: let them finish before cleanup
      await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .until { checkinRepository.findAll().let { it.size == 2 && it.all { checkin -> checkin.linkStatus == CheckinLinkStatus.SENT } } }
      checkinRepository.findAll()
    } finally {
      ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore)
    }

    assertEquals(listOf(CheckinStatus.CREATED, CheckinStatus.CREATED), checkins.map { it.status })
    assertEquals(
      checkins.map { it.uuid to DomainEventType.V2_CHECKIN_SCHEDULED }.toSet(),
      deliusEventRepository.findAll().filter { it.eventType == DomainEventType.V2_CHECKIN_SCHEDULED }.map { it.checkinUuid to it.eventType }.toSet(),
    )
  }

  @Test
  fun `check-ins stopped while the job waits for NDelius - neither the upcoming check-in nor a new one is created`() {
    val offender = offenderRepository.findByCrn("A000001").orElseThrow()
    val scheduled = checkinRepository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.SCHEDULED,
        dueDate = clock.today(),
        createdAt = clock.instant(),
        createdBy = "SYSTEM",
      ),
    )
    whenever(ndeliusApiClient.getContactDetailsForMultiple(any())).thenAnswer {
      if (offender.crn in it.getArgument<List<String>>(0)) {
        jdbcTemplate.update("update offender_v2 set status = 'INACTIVE' where id = ?", offender.id)
        jdbcTemplate.update("update offender_checkin_v2 set status = 'CANCELLED' where id = ?", scheduled.id)
      }
      contactDetailsFor(it.getArgument(0))
    }

    job.process()

    assertEquals(
      listOf(CheckinStatus.CANCELLED),
      checkinRepository.findAll().filter { it.offender.id == offender.id }.map { it.status },
    )
  }

  private fun contactDetailsFor(crns: List<String>): List<ContactDetails> {
    val practitionerDetails = PractitionerDetails(name = Name("John", "Smith"), email = "foo@example.com")
    return listOf(
      ContactDetails(
        crn = "A000001",
        name = Name("John", "Smith"),
        email = "a1@example.com",
        practitioner = practitionerDetails,
        events = listOf(
          Event(
            number = 1L,
            mainOffence = CodedDescription("OFF01", "Test"),
            sentence = null,
          ),
        ),
        dateOfBirth = LocalDate.of(1980, 1, 1),
      ),
      ContactDetails(
        crn = "A000002",
        name = Name("John", "Smith"),
        email = "a2@example.com",
        practitioner = practitionerDetails,
        events = listOf(
          Event(number = 1L, mainOffence = CodedDescription("OFF01", "Test"), sentence = null),
        ),
        dateOfBirth = LocalDate.of(1980, 1, 1),
      ),
    ).filter { it.crn in crns }
  }
}
