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
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotifyGatewayService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.V2CheckinCreationJob
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationV2Service
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@TestPropertySource(properties = ["app.scheduling.v2-checkin-creation.chunk-size=1"])
class V2CheckinCreationJobIT : IntegrationTestBase() {

  @Value("\${app.scheduling.v2-checkin-creation.chunk-size}")
  var chunkSize: Int = 0

  @Autowired lateinit var jdbcTemplate: JdbcTemplate

  @Autowired lateinit var clock: Clock

  @Autowired lateinit var offenderRepository: OffenderV2Repository

  @Autowired lateinit var checkinRespository: OffenderCheckinV2Repository

  @Autowired lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired lateinit var checkinCreationService: CheckinCreationService

  @Autowired lateinit var offenderDeactivationService: OffenderDeactivationV2Service

  @Autowired lateinit var jobLogRepository: JobLogV2Repository

  @Autowired lateinit var entityManager: EntityManager

  @Autowired lateinit var job: V2CheckinCreationJob

  @MockitoBean lateinit var ndeliusApiClient: INdiliusApiClient

  @MockitoBean lateinit var domainEventPublisher: DomainEventPublisher

  @MockitoBean lateinit var notifyGateway: NotifyGatewayService

  @BeforeEach
  fun setup() {
    offenderTemplate.copy(crn = "A000001", uuid = UUID.randomUUID(), firstCheckin = clock.today(), status = OffenderStatus.VERIFIED)
      .toEntity().let { offenderRepository.save(it) }
    offenderTemplate.copy(crn = "A000002", uuid = UUID.randomUUID(), firstCheckin = clock.today(), status = OffenderStatus.VERIFIED)
      .toEntity().let { offenderRepository.save(it) }

    val practitionerDetails = PractitionerDetails(Name("John", "Smith"), "foo@example.com")
    whenever(ndeliusApiClient.getContactDetailsForMultiple(any()))
      .thenAnswer { invocation ->
        val crns = invocation.getArgument<List<String>>(0)
        listOf(
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
          ),
          ContactDetails(
            crn = "A000002",
            name = Name("John", "Smith"),
            email = "a2@example.com",
            practitioner = practitionerDetails,
            events = listOf(
              Event(number = 1L, mainOffence = CodedDescription("OFF01", "Test"), sentence = null),
            ),
          ),
        ).filter { it.crn in crns }
      }

    whenever(notifyGateway.send(any(), any(), any(), any(), any()))
      .thenAnswer { UUID.randomUUID() }
  }

  @AfterEach
  fun cleanDb() {
    jdbcTemplate.update("TRUNCATE TABLE generic_notification_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_checkin_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE event_audit_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE outbox_items RESTART IDENTITY CASCADE")

    reset(ndeliusApiClient, domainEventPublisher, notifyGateway)
  }

  @Test
  fun `run checkin creation job`() {
    job.process()

    await()
      .atMost(Duration.ofSeconds(10000))
      .pollInterval(100, TimeUnit.MILLISECONDS)
      .untilAsserted {
        val checkins = checkinRespository.findAll().associateBy { it.offender.crn }
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
}
