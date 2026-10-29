package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinSubmittedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.checkinDateTime
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinMode
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.CheckinCreationJob
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.CheckinExpiryJob
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * ESUP-2223: scheduling the next check-in. The feature flag is switched on per test, so this
 * shares the default test context instead of starting another.
 */
class UpcomingCheckinServiceIT : IntegrationTestBase() {

  @Autowired
  private lateinit var service: UpcomingCheckinService

  @Autowired
  private lateinit var checkinCreationService: CheckinCreationService

  @Autowired
  private lateinit var checkinPersistenceService: CheckinPersistenceService

  @Autowired
  private lateinit var checkinExpiryJob: CheckinExpiryJob

  @Autowired
  private lateinit var checkinCreationJob: CheckinCreationJob

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  private lateinit var deliusEventRepository: CheckinDeliusEventRepository

  @Autowired
  private lateinit var appConfig: AppConfig

  @Autowired
  private lateinit var jobLogRepository: JobLogRepository

  @Autowired
  private lateinit var transactionTemplate: TransactionTemplate

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  private lateinit var clock: Clock

  private lateinit var featuresBefore: Set<Feature>

  private val today get() = clock.today()

  @BeforeEach
  fun enableFeature() {
    featuresBefore = appConfig.enabledFeatures
    setFeatures(featuresBefore + Feature.ESUP_2223)
  }

  @AfterEach
  fun tearDown() {
    setFeatures(featuresBefore)
    jdbcTemplate.update("TRUNCATE TABLE checkin_delius_event_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE event_audit_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE generic_notification_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE job_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE outbox_items RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_checkin_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_v2 RESTART IDENTITY CASCADE")
  }

  @Test
  fun `schedules the first check-in and records it for NDelius`() {
    val offender = saveOffender(firstCheckin = today.plusDays(3), currentEvent = 4)

    service.scheduleNext(offender.id)

    val scheduled = scheduledCheckins(offender).single()
    assertThat(scheduled.dueDate).isEqualTo(today.plusDays(3))
    val event = deliusEventRepository.findAllByCheckinUuidOrderById(scheduled.uuid).single()
    assertThat(event.eventType).isEqualTo(DomainEventType.V2_CHECKIN_SCHEDULED)
    assertThat(event.dateTime).isEqualTo(checkinDateTime(today.plusDays(3)).toInstant())
    assertThat(event.eventNumber).isEqualTo(4)
  }

  @Test
  fun `the caller's event number wins over the one on record`() {
    val offender = saveOffender(firstCheckin = today.plusDays(3), currentEvent = 4)

    service.scheduleNext(offender.id, eventNumber = 7)

    assertThat(deliusEventRepository.findAll().single().eventNumber).isEqualTo(7)
  }

  @Test
  fun `does nothing with the feature off`() {
    setFeatures(featuresBefore - Feature.ESUP_2223)
    val offender = saveOffender(firstCheckin = today.plusDays(3))

    service.scheduleNext(offender.id)

    assertThat(scheduledCheckins(offender)).isEmpty()
  }

  @Test
  fun `schedules only one check-in`() {
    val offender = saveOffender(firstCheckin = today.plusDays(3))

    service.scheduleNext(offender.id)
    service.scheduleNext(offender.id)

    assertThat(scheduledCheckins(offender)).hasSize(1)
    assertThat(deliusEventRepository.findAll()).hasSize(1)
  }

  @Test
  fun `waits while a check-in is open`() {
    val offender = saveOffender(firstCheckin = today)
    saveCheckin(offender, CheckinStatus.CREATED, today)

    service.scheduleNext(offender.id)

    assertThat(scheduledCheckins(offender)).isEmpty()
  }

  @Test
  fun `after today's check-in, schedules the one after it`() {
    val offender = saveOffender(firstCheckin = today.minusWeeks(1))
    saveCheckin(offender, CheckinStatus.SUBMITTED, today)

    service.scheduleNext(offender.id)

    assertThat(scheduledCheckins(offender).single().dueDate).isEqualTo(today.plusWeeks(1))
  }

  @Test
  fun `schedules today when today's check-in hasn't been created yet`() {
    val offender = saveOffender(firstCheckin = today.minusWeeks(1))

    service.scheduleNext(offender.id)

    assertThat(scheduledCheckins(offender).single().dueDate).isEqualTo(today)
  }

  @Test
  fun `after today's creation job has started, schedules the next date after today`() {
    jobLogRepository.save(JobLog(jobType = CheckinCreationJob.JOB_TYPE, createdAt = clock.instant()))
    val offender = saveOffender(firstCheckin = today.minusWeeks(1))

    service.scheduleNext(offender.id)

    assertThat(scheduledCheckins(offender).single().dueDate).isEqualTo(today.plusWeeks(1))
  }

  @Test
  fun `ad hoc schedules the arranged date, and nothing once it has passed`() {
    val upcoming = saveOffender(firstCheckin = today.plusDays(2), mode = CheckinMode.AD_HOC)
    val done = saveOffender(firstCheckin = today.minusDays(2), mode = CheckinMode.AD_HOC)

    service.scheduleNext(upcoming.id)
    service.scheduleNext(done.id)

    assertThat(scheduledCheckins(upcoming).single().dueDate).isEqualTo(today.plusDays(2))
    assertThat(scheduledCheckins(done)).isEmpty()
  }

  @Test
  fun `does nothing for an offender whose check-ins are stopped`() {
    val offender = saveOffender(firstCheckin = today.plusDays(3), status = OffenderStatus.INACTIVE)

    service.scheduleNext(offender.id)

    assertThat(scheduledCheckins(offender)).isEmpty()
  }

  @Test
  fun `cancels a scheduled check-in whose date passed without it being created, then schedules the next`() {
    val offender = saveOffender(firstCheckin = today.minusWeeks(1).plusDays(1), currentEvent = 4)
    val missed = saveCheckin(offender, CheckinStatus.SCHEDULED, today.minusWeeks(1).plusDays(1))

    service.scheduleNext(offender.id)

    assertThat(checkinRepository.findByUuid(missed.uuid).get().status).isEqualTo(CheckinStatus.CANCELLED)
    val cancelled = deliusEventRepository.findAllByCheckinUuidOrderById(missed.uuid).single()
    assertThat(cancelled.eventType).isEqualTo(DomainEventType.V2_CHECKIN_CANCELLED)
    assertThat(cancelled.eventNumber).isNull()
    assertThat(scheduledCheckins(offender).single().dueDate).isEqualTo(today.plusDays(1))
  }

  @Test
  fun `inside a transaction, schedules once it commits`() {
    val offender = saveOffender(firstCheckin = today.plusDays(3))

    transactionTemplate.executeWithoutResult {
      service.scheduleNext(offender.id)
      assertThat(scheduledCheckins(offender)).isEmpty()
    }
    assertThat(scheduledCheckins(offender)).hasSize(1)

    val other = saveOffender(firstCheckin = today.plusDays(3))
    transactionTemplate.executeWithoutResult {
      service.scheduleNext(other.id)
      it.setRollbackOnly()
    }
    assertThat(scheduledCheckins(other)).isEmpty()
  }

  @Test
  fun `a check-in created without being scheduled is recorded as scheduled, and an activated one isn't recorded again`() {
    val unscheduled = saveOffender(firstCheckin = today)
    val scheduled = saveOffender(firstCheckin = today)
    service.scheduleNext(scheduled.id)

    val created = transactionTemplate.execute { checkinCreationService.createCheckinForOffender(unscheduled, today, "PRACT001", contactDetails(unscheduled)) }!!
    val activated = transactionTemplate.execute { checkinCreationService.createCheckinForOffender(scheduled, today, "PRACT001", contactDetails(scheduled)) }!!

    assertThat(deliusEventRepository.findAllByCheckinUuidOrderById(created.uuid).map { it.eventType }).containsExactly(DomainEventType.V2_CHECKIN_SCHEDULED)
    assertThat(deliusEventRepository.findAllByCheckinUuidOrderById(activated.uuid).map { it.eventType }).containsExactly(DomainEventType.V2_CHECKIN_SCHEDULED)
  }

  @Test
  fun `setting a schedule through update_details schedules the first check-in`() {
    val offender = saveOffender(firstCheckin = today.plusDays(10))
    val firstCheckin = today.plusDays(4)

    webTestClient.post()
      .uri("/v2/offenders/${offender.uuid}/update_details")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{"checkinSchedule":{"requestedBy":"PRACT001","firstCheckin":"$firstCheckin","checkinInterval":"WEEKLY","mode":"SCHEDULED"}}""")
      .exchange()
      .expectStatus().isOk

    assertThat(scheduledCheckins(offender).single().dueDate).isEqualTo(firstCheckin)
  }

  @Test
  fun `a changed schedule moves the upcoming check-in, keeping its UUID`() {
    val offender = saveOffender(firstCheckin = today.plusDays(3), currentEvent = 4)
    service.scheduleNext(offender.id)
    val scheduled = scheduledCheckins(offender).single()

    offender.firstCheckin = today.plusDays(5)
    offenderRepository.save(offender)
    service.scheduleNext(offender.id)

    val moved = scheduledCheckins(offender).single()
    assertThat(moved.uuid).isEqualTo(scheduled.uuid)
    assertThat(moved.dueDate).isEqualTo(today.plusDays(5))
    val rescheduled = deliusEventRepository.findAllByCheckinUuidOrderById(scheduled.uuid).last()
    assertThat(rescheduled.eventType).isEqualTo(DomainEventType.V2_CHECKIN_RESCHEDULED)
    assertThat(rescheduled.dateTime).isEqualTo(checkinDateTime(today.plusDays(5)).toInstant())
    assertThat(rescheduled.notes).startsWith("Check in moved to: ")
    assertThat(rescheduled.eventNumber).isNull()
  }

  @Test
  fun `an unchanged schedule leaves the upcoming check-in alone`() {
    val offender = saveOffender(firstCheckin = today.plusDays(3))
    service.scheduleNext(offender.id)

    service.scheduleNext(offender.id)

    assertThat(deliusEventRepository.findAll().map { it.eventType }).containsExactly(DomainEventType.V2_CHECKIN_SCHEDULED)
  }

  @Test
  fun `the upcoming check-in is cancelled when there's no longer a next date`() {
    val offender = saveOffender(firstCheckin = today.plusDays(3), mode = CheckinMode.AD_HOC)
    service.scheduleNext(offender.id)
    val scheduled = scheduledCheckins(offender).single()

    offender.firstCheckin = null
    offenderRepository.save(offender)
    service.scheduleNext(offender.id)

    assertThat(checkinRepository.findByUuid(scheduled.uuid).get().status).isEqualTo(CheckinStatus.CANCELLED)
    val cancelled = deliusEventRepository.findAllByCheckinUuidOrderById(scheduled.uuid).last()
    assertThat(cancelled.eventType).isEqualTo(DomainEventType.V2_CHECKIN_CANCELLED)
    assertThat(cancelled.notes).isEqualTo("Reason: ${UpcomingCheckinService.SCHEDULE_CHANGED_REASON}")
  }

  @Test
  fun `an upcoming check-in alongside an open one is cancelled`() {
    val offender = saveOffender(firstCheckin = today.plusDays(3))
    service.scheduleNext(offender.id)
    val scheduled = scheduledCheckins(offender).single()
    saveCheckin(offender, CheckinStatus.CREATED, today)

    service.scheduleNext(offender.id)

    assertThat(checkinRepository.findByUuid(scheduled.uuid).get().status).isEqualTo(CheckinStatus.CANCELLED)
  }

  @Test
  fun `changing the schedule through update_details moves the upcoming check-in`() {
    val offender = saveOffender(firstCheckin = today.plusDays(3))
    service.scheduleNext(offender.id)
    val scheduled = scheduledCheckins(offender).single()
    val firstCheckin = today.plusDays(4)

    webTestClient.post()
      .uri("/v2/offenders/${offender.uuid}/update_details")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{"checkinSchedule":{"requestedBy":"PRACT001","firstCheckin":"$firstCheckin","checkinInterval":"WEEKLY","mode":"SCHEDULED"}}""")
      .exchange()
      .expectStatus().isOk

    assertThat(scheduledCheckins(offender).single().let { it.uuid to it.dueDate }).isEqualTo(scheduled.uuid to firstCheckin)
  }

  @Test
  fun `submitting a check-in schedules the next`() {
    val offender = saveOffender(firstCheckin = today)
    val checkin = saveCheckin(offender, CheckinStatus.SUBMITTED, today)
    val event = CheckinSubmittedEvent(checkin.id, offender.id, offender.practitionerId, checkin.dto(contactDetails(offender)), offender.contactPreference)

    checkinPersistenceService.checkinSubmission(checkin, event)

    assertThat(scheduledCheckins(offender).single().dueDate).isEqualTo(today.plusWeeks(1))
  }

  @Test
  fun `a missed check-in schedules the next`() {
    val offender = saveOffender(firstCheckin = today.minusDays(4))
    saveCheckin(offender, CheckinStatus.CREATED, today.minusDays(4))

    checkinExpiryJob.process()

    assertThat(scheduledCheckins(offender).single().dueDate).isEqualTo(today.plusDays(3))
  }

  @Test
  fun `the creation job reschedules a scheduled check-in that wasn't created on its date`() {
    val offender = saveOffender(firstCheckin = today.minusDays(5), status = OffenderStatus.INACTIVE)
    saveCheckin(offender, CheckinStatus.SCHEDULED, today.minusDays(5))
    // verified after the job would have created it
    offender.status = OffenderStatus.VERIFIED
    offenderRepository.save(offender)

    checkinCreationJob.process()

    assertThat(scheduledCheckins(offender).single().dueDate).isEqualTo(today.plusDays(2))
  }

  private fun setFeatures(features: Set<Feature>) = ReflectionTestUtils.setField(appConfig, "enabledFeatures", features)

  private fun scheduledCheckins(offender: Offender) = checkinRepository.findAllByOffenderAndStatus(offender, CheckinStatus.SCHEDULED)

  private fun saveOffender(
    firstCheckin: LocalDate,
    mode: CheckinMode = CheckinMode.SCHEDULED,
    status: OffenderStatus = OffenderStatus.VERIFIED,
    currentEvent: Long? = null,
  ): Offender = offenderRepository.save(
    offenderTemplate.copy(
      uuid = UUID.randomUUID(),
      crn = "X${(100000..999999).random()}",
      status = status,
      firstCheckin = firstCheckin,
      mode = mode,
      checkinInterval = if (mode == CheckinMode.SCHEDULED) CheckinInterval.WEEKLY else null,
    ).toEntity().also { it.currentEvent = currentEvent },
  )

  private fun saveCheckin(offender: Offender, status: CheckinStatus, dueDate: LocalDate): OffenderCheckin = checkinRepository.save(
    OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = offender,
      status = status,
      dueDate = dueDate,
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
    ),
  )

  private fun contactDetails(offender: Offender) = ContactDetails(
    crn = offender.crn,
    name = Name("John", "Smith"),
    email = "pop@example.com",
    practitioner = PractitionerDetails(name = Name("Jane", "Jones"), email = "practitioner@example.com"),
    events = listOf(Event(number = 1L, mainOffence = CodedDescription("OFF01", "Test"), sentence = null)),
    dateOfBirth = LocalDate.of(1980, 1, 1),
  )
}
