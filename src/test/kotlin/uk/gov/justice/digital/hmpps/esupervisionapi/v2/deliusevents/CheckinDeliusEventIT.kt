package uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.after
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.MutableTestClock
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.TestClockConfiguration
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinSubmittedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Import(TestClockConfiguration::class)
class CheckinDeliusEventIT : IntegrationTestBase() {

  @Autowired
  private lateinit var service: CheckinDeliusEventService

  @Autowired
  private lateinit var repository: CheckinDeliusEventRepository

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  private lateinit var transactionTemplate: TransactionTemplate

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  private lateinit var clock: Clock

  @Autowired
  private lateinit var appConfig: AppConfig

  @Autowired
  private lateinit var checkinPersistenceService: CheckinPersistenceService

  @MockitoBean
  private lateinit var publisher: DomainEventPublisher

  private val minGap = Duration.ofSeconds(60)

  @BeforeEach
  fun setUp() {
    (clock as MutableTestClock).advanceTo(Instant.now())
    reset(publisher)
  }

  @AfterEach
  fun tearDown() {
    jdbcTemplate.update("TRUNCATE TABLE checkin_delius_event_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE event_audit_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE outbox_items RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_checkin_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_v2 RESTART IDENTITY CASCADE")
  }

  @Test
  fun `record requires a surrounding transaction`() {
    val checkin = saveCheckin()

    assertThrows<IllegalTransactionStateException> {
      service.record(DomainEventType.V2_CHECKIN_CANCELLED, checkin, "notes", eventNumber = 1)
    }
  }

  @Test
  fun `record stores the event with the check-in date at 9am London time`() {
    val checkin = saveCheckin()
    val dueDate = LocalDate.of(2026, 11, 3)

    record(DomainEventType.V2_CHECKIN_SCHEDULED, checkin, date = dueDate)

    val event = repository.findAllByCheckinUuidOrderById(checkin.uuid).single()
    assertThat(event.eventType).isEqualTo(DomainEventType.V2_CHECKIN_SCHEDULED)
    assertThat(event.crn).isEqualTo(checkin.offender.crn)
    assertThat(event.eventNumber).isEqualTo(2)
    assertThat(event.dateTime).isEqualTo(Instant.parse("2026-11-03T09:00:00Z"))
    assertThat(event.publishedAt).isNull()
  }

  @Test
  fun `each event type is recorded once per check-in, except rescheduling`() {
    val checkin = saveCheckin()

    assertThat(record(DomainEventType.V2_CHECKIN_SCHEDULED, checkin, date = clock.today())).isTrue()
    assertThat(record(DomainEventType.V2_CHECKIN_SCHEDULED, checkin, date = clock.today())).isFalse()
    assertThat(record(DomainEventType.V2_CHECKIN_RESCHEDULED, checkin, date = clock.today().plusDays(1))).isTrue()
    assertThat(record(DomainEventType.V2_CHECKIN_RESCHEDULED, checkin, date = clock.today().plusDays(2))).isTrue()

    assertThat(repository.findAllByCheckinUuidOrderById(checkin.uuid).map { it.eventType }).containsExactly(
      DomainEventType.V2_CHECKIN_SCHEDULED,
      DomainEventType.V2_CHECKIN_RESCHEDULED,
      DomainEventType.V2_CHECKIN_RESCHEDULED,
    )
  }

  @Test
  fun `only scheduled and rescheduled events have a date`() {
    val checkin = saveCheckin()

    assertThrows<IllegalArgumentException> { record(DomainEventType.V2_CHECKIN_SCHEDULED, checkin) }
    assertThrows<IllegalArgumentException> { record(DomainEventType.V2_CHECKIN_CANCELLED, checkin, date = clock.today()) }
    assertThrows<IllegalArgumentException> { record(DomainEventType.V2_CHECKIN_SUBMITTED, checkin) }
  }

  @Test
  fun `publishes a check-in's events one at a time, at least the minimum gap apart`() {
    val checkin = saveCheckin()
    record(DomainEventType.V2_CHECKIN_SCHEDULED, checkin, date = clock.today())
    record(DomainEventType.V2_CHECKIN_LINK_DELIVERED, checkin)

    assertThat(service.publishPending(10, minGap)).isEqualTo(1)
    assertThat(service.publishPending(10, minGap)).isEqualTo(0)

    clock.advanceBy(minGap.minusSeconds(1))
    assertThat(service.publishPending(10, minGap)).isEqualTo(0)

    clock.advanceBy(Duration.ofSeconds(2))
    assertThat(service.publishPending(10, minGap)).isEqualTo(1)

    val published = argumentCaptor<DomainEvent>()
    verify(publisher, times(2)).publishOrThrow(published.capture())
    assertThat(published.allValues.map { it.eventType }).containsExactly(
      "esupervision.check-in.scheduled",
      "esupervision.check-in.link.delivered",
    )
  }

  @Test
  fun `different check-ins are published independently`() {
    val first = saveCheckin()
    val second = saveCheckin()
    record(DomainEventType.V2_CHECKIN_SCHEDULED, first, date = clock.today())
    record(DomainEventType.V2_CHECKIN_SCHEDULED, second, date = clock.today())

    assertThat(service.publishPending(10, minGap)).isEqualTo(2)
  }

  @Test
  fun `the published message points at the event detail and carries the event number`() {
    val checkin = saveCheckin()
    record(DomainEventType.V2_CHECKIN_CANCELLED, checkin)
    val recorded = repository.findAllByCheckinUuidOrderById(checkin.uuid).single()

    service.publishPending(10, minGap)

    val message = argumentCaptor<DomainEvent>().also { verify(publisher).publishOrThrow(it.capture()) }.firstValue
    assertThat(message.detailUrl).isEqualTo("http://localhost:8080/v2/events/checkin-cancelled/${recorded.eventId}")
    assertThat(message.additionalInformation?.eventNumber).isEqualTo(2)
    assertThat(message.personReference?.identifiers?.single()?.value).isEqualTo(checkin.offender.crn)
    assertThat(message.occurredAt.toInstant()).isEqualTo(recorded.occurredAt)
  }

  @Test
  fun `a failed publish is retried, and holds back the check-in's later events`() {
    val checkin = saveCheckin()
    record(DomainEventType.V2_CHECKIN_SCHEDULED, checkin, date = clock.today())
    record(DomainEventType.V2_CHECKIN_CANCELLED, checkin)
    doThrow(RuntimeException("topic unavailable")).whenever(publisher).publishOrThrow(any())

    assertThat(service.publishPending(10, minGap)).isEqualTo(0)

    val failed = repository.findAllByCheckinUuidOrderById(checkin.uuid).first()
    assertThat(failed.publishAttempts).isEqualTo(1)
    assertThat(failed.lastError).isEqualTo("topic unavailable")
    verify(publisher, times(1)).publishOrThrow(any())

    reset(publisher)
    assertThat(service.publishPending(10, minGap)).isEqualTo(1)
    val retried = repository.findAllByCheckinUuidOrderById(checkin.uuid)
    assertThat(retried.map { it.publishedAt != null }).containsExactly(true, false)
    assertThat(retried.first().publishAttempts).isEqualTo(2)
    assertThat(retried.first().lastError).isNull()
  }

  @Test
  fun `a run stops after a few failures in a row`() {
    repeat(5) { record(DomainEventType.V2_CHECKIN_CANCELLED, saveCheckin()) }
    doThrow(RuntimeException("topic unavailable")).whenever(publisher).publishOrThrow(any())

    assertThat(service.publishPending(10, minGap)).isEqualTo(0)

    verify(publisher, times(CheckinDeliusEventService.MAX_FAILURES_IN_A_ROW)).publishOrThrow(any())
  }

  @Test
  fun `a check-in update is published in order, pointing at the check-in's detail`() {
    val checkin = saveCheckin()
    record(DomainEventType.V2_CHECKIN_SCHEDULED, checkin, date = clock.today())
    transactionTemplate.execute { service.recordCheckinUpdate(DomainEventType.V2_CHECKIN_SUBMITTED, checkin) }

    service.publishPending(10, minGap)
    clock.advanceBy(minGap.plusSeconds(1))
    service.publishPending(10, minGap)

    val published = argumentCaptor<DomainEvent>().also { verify(publisher, times(2)).publishOrThrow(it.capture()) }.allValues
    assertThat(published.map { it.eventType }).containsExactly("esupervision.check-in.scheduled", "esupervision.check-in.received")
    assertThat(published[1].detailUrl).isEqualTo("http://localhost:8080/v2/events/checkin-submitted/${checkin.uuid}")
    assertThat(published[1].additionalInformation?.eventNumber).isNull()
  }

  @Test
  fun `annotations can be recorded more than once, each pointing at its log entry`() {
    val checkin = saveCheckin()
    val annotations = listOf(UUID.randomUUID(), UUID.randomUUID())

    annotations.forEach { id -> transactionTemplate.execute { service.recordCheckinUpdate(DomainEventType.V2_CHECKIN_ANNOTATED, checkin, detailId = id) } }

    assertThat(repository.findAllByCheckinUuidOrderById(checkin.uuid).map { it.detailId }).containsExactlyElementsOf(annotations)
  }

  @Test
  fun `lifecycle and check-in update events are recorded through their own methods`() {
    val checkin = saveCheckin()

    assertThrows<IllegalArgumentException> { transactionTemplate.execute { service.recordCheckinUpdate(DomainEventType.V2_CHECKIN_CANCELLED, checkin) } }
    assertThrows<IllegalArgumentException> { record(DomainEventType.V2_CHECKIN_SUBMITTED, checkin) }
  }

  @Test
  fun `with the feature on, a submission is recorded for the relay instead of published directly`() {
    val featuresBefore = appConfig.enabledFeatures
    ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore + Feature.ESUP_2223)
    try {
      val checkin = saveCheckin()
      checkin.status = CheckinStatus.SUBMITTED
      checkin.submittedAt = clock.instant()
      val event = CheckinSubmittedEvent(checkin.id, checkin.offender.id, "PRACT001", checkin.dto(), checkin.offender.contactPreference)

      checkinPersistenceService.checkinSubmission(checkin, event)

      assertThat(repository.findAllByCheckinUuidOrderById(checkin.uuid).single().eventType).isEqualTo(DomainEventType.V2_CHECKIN_SUBMITTED)
      verify(publisher, after(1000).never()).publish(any())
    } finally {
      ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore)
    }
  }

  @Test
  fun `event detail endpoint returns the recorded event`() {
    val checkin = saveCheckin()
    record(DomainEventType.V2_CHECKIN_SCHEDULED, checkin, date = LocalDate.of(2026, 7, 1))
    val event = repository.findAllByCheckinUuidOrderById(checkin.uuid).single()

    webTestClient.get()
      .uri("/v2/events/checkin-scheduled/${event.eventId}")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__CHECK_IN__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.checkinUuid").isEqualTo(checkin.uuid.toString())
      .jsonPath("$.eventType").isEqualTo("CHECKIN_SCHEDULED")
      .jsonPath("$.notes").isEqualTo("Check in due: Wednesday 1 July 2026")
      // 9am British Summer Time, keeping the offset so NDelius takes the right local date
      .jsonPath("$.dateTime").isEqualTo("2026-07-01T09:00:00+01:00")

    webTestClient.get()
      .uri("/v2/events/checkin-cancelled/${event.eventId}")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__CHECK_IN__RO")))
      .exchange()
      .expectStatus().isNotFound
  }

  private fun record(type: DomainEventType, checkin: OffenderCheckin, date: LocalDate? = null): Boolean = transactionTemplate.execute {
    val notes = date?.let { CheckinDeliusEventNotes.scheduled(it) } ?: "notes"
    service.record(type, checkin, notes, eventNumber = 2, date = date)
  }!!

  private fun saveCheckin(): OffenderCheckin {
    val offender = offenderRepository.save(offenderTemplate.copy(uuid = UUID.randomUUID(), crn = "X${(100000..999999).random()}").toEntity())
    return checkinRepository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.SCHEDULED,
        dueDate = clock.today(),
        createdAt = clock.instant(),
        createdBy = "SYSTEM",
      ),
    )
  }

  private fun Clock.advanceBy(duration: Duration) = (this as MutableTestClock).advanceBy(duration)
}
