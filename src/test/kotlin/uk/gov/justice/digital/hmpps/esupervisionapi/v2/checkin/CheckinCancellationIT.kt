package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinCancellation
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinCancelledEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinLinkStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinSubmittedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventDetailService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.LogEntryType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventNotes
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinMode
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotificationOrchestratorService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotifyGatewayService
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

class CheckinCancellationIT : IntegrationTestBase() {

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  private lateinit var deliusEventRepository: CheckinDeliusEventRepository

  @Autowired
  private lateinit var deliusEventService: CheckinDeliusEventService

  @Autowired
  private lateinit var transactionTemplate: TransactionTemplate

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  private lateinit var appConfig: AppConfig

  @Autowired
  private lateinit var eventDetailService: EventDetailService

  @Autowired
  private lateinit var persistenceService: CheckinPersistenceService

  @Autowired
  private lateinit var upcomingCheckinService: UpcomingCheckinService

  @MockitoBean
  private lateinit var ndiliusApiClient: INdiliusApiClient

  @MockitoSpyBean
  private lateinit var notificationService: NotificationOrchestratorService

  @MockitoBean
  private lateinit var notifyGatewayService: NotifyGatewayService

  @MockitoBean
  private lateinit var publisher: DomainEventPublisher

  @MockitoBean
  private lateinit var s3UploadService: S3UploadService

  private lateinit var featuresBefore: Set<Feature>

  @BeforeEach
  fun setUp() {
    featuresBefore = appConfig.enabledFeatures
    reset(notificationService)
    doNothing().whenever(notificationService).sendCheckinCancelledNotifications(any())
  }

  @AfterEach
  fun tearDown() {
    ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore)
    jdbcTemplate.update("TRUNCATE TABLE generic_notification_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE checkin_delius_event_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE event_audit_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE outbox_items RESTART IDENTITY CASCADE")
    jdbcTemplate.update("DELETE FROM question_list_assignment")
    jdbcTemplate.update("TRUNCATE TABLE offender_event_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_checkin_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_v2 RESTART IDENTITY CASCADE")
  }

  @Test
  fun `cancelling an open check-in records the reason and tells the offender`() {
    val checkin = saveCheckin(CheckinStatus.CREATED)

    cancel(checkin.uuid, reason = "Attending in person instead")
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.status").isEqualTo("CANCELLED")
      .jsonPath("$.cancelledBy").isEqualTo("PRACT_CANCEL")
      .jsonPath("$.sensitive").isEqualTo(false)

    val saved = checkinRepository.findByUuid(checkin.uuid).get()
    assertThat(saved.status).isEqualTo(CheckinStatus.CANCELLED)
    assertThat(saved.cancelledBy).isEqualTo("PRACT_CANCEL")
    assertThat(saved.cancelledAt).isNotNull()

    val log = cancellationLog(saved)
    assertThat(log.notes).isEqualTo("Attending in person instead")
    assertThat(log.practitioner).isEqualTo("PRACT_CANCEL")

    val event = argumentCaptor<CheckinCancelledEvent>()
    verify(notificationService, timeout(2000)).sendCheckinCancelledNotifications(event.capture())
    assertThat(event.firstValue.previousStatus).isEqualTo(CheckinStatus.CREATED)
    assertThat(event.firstValue.inviteSent).isTrue()
  }

  @ParameterizedTest
  @EnumSource(CheckinMode::class)
  fun `cancelling an upcoming check-in uses the invitation-not-sent variant`(mode: CheckinMode) {
    val checkin = saveCheckin(CheckinStatus.SCHEDULED, dueDate = LocalDate.now().plusDays(3), mode = mode)

    cancel(checkin.uuid).expectStatus().isOk

    val event = argumentCaptor<CheckinCancelledEvent>()
    verify(notificationService, timeout(2000)).sendCheckinCancelledNotifications(event.capture())
    assertThat(event.firstValue.previousStatus).isEqualTo(CheckinStatus.SCHEDULED)
    assertThat(event.firstValue.inviteSent).isFalse()
  }

  @ParameterizedTest
  @CsvSource(
    "CREATED,SENT,PHONE,OffenderCheckinCancelled,SMS,827fce47-9d47-4ef2-a658-189d6853b4a4",
    "CREATED,SENT,EMAIL,OffenderCheckinCancelled,EMAIL,c525e883-0524-47b3-92ee-901bce7e7d8e",
    "SCHEDULED,,PHONE,OffenderCheckinCancelledBeforeInvite,SMS,1357812e-fb3d-4351-809b-65fd8b569a40",
    "SCHEDULED,,EMAIL,OffenderCheckinCancelledBeforeInvite,EMAIL,2312df50-470c-45b5-bb4a-de252254acd2",
  )
  fun `cancellation events send and persist the configured template for their channel`(
    previousStatus: CheckinStatus,
    linkStatus: CheckinLinkStatus?,
    contactPreference: ContactPreference,
    notificationType: NotificationType,
    channel: String,
    templateId: String,
  ) {
    val checkin = saveCheckin(previousStatus, linkStatus = linkStatus, contactPreference = contactPreference)
    whenever(notifyGatewayService.send(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID())
    doCallRealMethod().whenever(notificationService).sendCheckinCancelledNotifications(any())

    cancel(checkin.uuid).expectStatus().isOk

    val captor = argumentCaptor<CheckinCancelledEvent>()
    verify(notificationService, timeout(2000)).sendCheckinCancelledNotifications(captor.capture())
    val event = captor.firstValue
    assertThat(event.linkStatus).isEqualTo(linkStatus)

    await().atMost(5, TimeUnit.SECONDS).untilAsserted {
      verify(notifyGatewayService).send(
        eq(channel),
        eq(templateId),
        eq(if (contactPreference == ContactPreference.PHONE) "07700900000" else "john@example.com"),
        any(),
        any(),
      )
      val notification = jdbcTemplate.queryForMap("select * from generic_notification_v2 where offender_id = ?", event.offenderId)
      assertThat(notification["template_id"]).isEqualTo(templateId)
      assertThat(notification["event_type"]).isEqualTo(notificationType.name)
      assertThat(notification["status"]).isEqualTo("sent")
    }
  }

  @Test
  fun `a sensitive reason marks the check-in and its log entry sensitive`() {
    val checkin = saveCheckin(CheckinStatus.CREATED)

    cancel(checkin.uuid, sensitive = true)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.sensitive").isEqualTo(true)

    val saved = checkinRepository.findByUuid(checkin.uuid).get()
    assertThat(saved.sensitive).isTrue()
    assertThat(cancellationLog(saved).sensitive).isTrue()
  }

  @Test
  fun `the cancellation reason is in the check-in's logs`() {
    val checkin = saveCheckin(CheckinStatus.CREATED)
    cancel(checkin.uuid, reason = "Attending in person instead").expectStatus().isOk

    webTestClient.get()
      .uri("/v2/offender_checkins/{uuid}", checkin.uuid)
      .headers(setAuthorisation(roles = listOf(UI_ROLE)))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.checkinLogs.logs[0].logEntryType").isEqualTo("OFFENDER_CHECKIN_CANCELLED")
      .jsonPath("$.checkinLogs.logs[0].notes").isEqualTo("Attending in person instead")
  }

  @ParameterizedTest
  @EnumSource(CheckinStatus::class, names = ["SUBMITTED", "REVIEWED", "EXPIRED", "CANCELLED"])
  fun `a check-in that is not upcoming or open can't be cancelled`(status: CheckinStatus) {
    val checkin = saveCheckin(status)

    cancel(checkin.uuid).expectStatus().isEqualTo(409)

    assertThat(checkinRepository.findByUuid(checkin.uuid).get().status).isEqualTo(status)
    verify(notificationService, never()).sendCheckinCancelledNotifications(any())
  }

  @Test
  fun `an open check-in past its window can't be cancelled`() {
    val checkin = saveCheckin(CheckinStatus.CREATED, dueDate = LocalDate.now().minusDays(10))

    cancel(checkin.uuid).expectStatus().isEqualTo(409)

    assertThat(checkinRepository.findByUuid(checkin.uuid).get().status).isEqualTo(CheckinStatus.CREATED)
  }

  @Test
  fun `an unknown check-in is 404`() {
    cancel(UUID.randomUUID()).expectStatus().isNotFound
  }

  @Test
  fun `a blank reason is 400`() {
    val checkin = saveCheckin(CheckinStatus.CREATED)

    cancel(checkin.uuid, reason = " ").expectStatus().isBadRequest

    assertThat(checkinRepository.findByUuid(checkin.uuid).get().status).isEqualTo(CheckinStatus.CREATED)
  }

  @Test
  fun `a sensitive flag that isn't a boolean is 400`() {
    val checkin = saveCheckin(CheckinStatus.CREATED)

    webTestClient.post()
      .uri("/v2/offender_checkins/{uuid}/cancel", checkin.uuid)
      .headers(setAuthorisation(roles = listOf(UI_ROLE)))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{"cancelledBy":"PRACT_CANCEL","reason":"r","sensitive":"yes"}""")
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `without the UI role it is forbidden`() {
    val checkin = saveCheckin(CheckinStatus.CREATED)

    webTestClient.post()
      .uri("/v2/offender_checkins/{uuid}/cancel", checkin.uuid)
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(body("PRACT_CANCEL", "reason", false))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `with ESUP-2223 on, a check-in NDelius has gets a cancelled event with the reason`() {
    ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore + Feature.ESUP_2223)
    val checkin = saveCheckin(CheckinStatus.SCHEDULED, dueDate = LocalDate.now().plusDays(3))
    transactionTemplate.executeWithoutResult {
      deliusEventService.record(DomainEventType.V2_CHECKIN_SCHEDULED, checkin, "notes", eventNumber = 1, date = checkin.dueDate)
    }

    cancel(checkin.uuid, reason = "Attending in person instead", sensitive = true).expectStatus().isOk

    val cancelled = deliusEventRepository.findAllByCheckinUuidOrderById(checkin.uuid).last()
    assertThat(cancelled.eventType).isEqualTo(DomainEventType.V2_CHECKIN_CANCELLED)
    assertThat(cancelled.notes).isEqualTo(CheckinDeliusEventNotes.cancelled("Attending in person instead"))
    assertThat(cancelled.eventNumber).isNull()

    val detail = eventDetailService.getEventDetail("/v2/events/checkin-cancelled/${cancelled.eventId}")!!
    assertThat(detail.notes).isEqualTo("Check in status: Cancelled\n\nReason: Attending in person instead")
    assertThat(detail.sensitive).isTrue()
  }

  @Test
  fun `with ESUP-2223 on, a check-in NDelius doesn't have gets no cancelled event`() {
    ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore + Feature.ESUP_2223)
    val checkin = saveCheckin(CheckinStatus.CREATED)

    cancel(checkin.uuid).expectStatus().isOk

    assertThat(deliusEventRepository.findAllByCheckinUuidOrderById(checkin.uuid)).isEmpty()
  }

  @Test
  fun `with ESUP-2223 off, no NDelius event is recorded`() {
    val checkin = saveCheckin(CheckinStatus.CREATED)

    cancel(checkin.uuid).expectStatus().isOk

    assertThat(deliusEventRepository.findAllByCheckinUuidOrderById(checkin.uuid)).isEmpty()
  }

  @Test
  fun `with ESUP-2223 on, cancelling an upcoming check-in schedules the one after it`() {
    ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore + Feature.ESUP_2223)
    val today = LocalDate.now()
    val checkin = saveCheckin(CheckinStatus.SCHEDULED, dueDate = today.plusDays(3), firstCheckin = today.plusDays(3))

    cancel(checkin.uuid).expectStatus().isOk

    val next = scheduledCheckins(checkin.offender).single()
    assertThat(next.dueDate).isEqualTo(today.plusDays(10))
    assertThat(deliusEventRepository.findAllByCheckinUuidOrderById(next.uuid).map { it.eventType })
      .containsExactly(DomainEventType.V2_CHECKIN_SCHEDULED)
  }

  @Test
  fun `with ESUP-2223 on, cancelling an open check-in schedules the next one`() {
    ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore + Feature.ESUP_2223)
    val today = LocalDate.now()
    val checkin = saveCheckin(CheckinStatus.CREATED, dueDate = today, firstCheckin = today)

    cancel(checkin.uuid).expectStatus().isOk

    assertThat(scheduledCheckins(checkin.offender).single().dueDate).isEqualTo(today.plusDays(7))
  }

  @Test
  fun `a cancelled date stays cancelled when the contact preference changes, and is rebooked when the schedule is set`() {
    ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore + Feature.ESUP_2223)
    val today = LocalDate.now()
    val checkin = saveCheckin(CheckinStatus.SCHEDULED, dueDate = today.plusDays(3), firstCheckin = today.plusDays(3))
    cancel(checkin.uuid).expectStatus().isOk

    updateDetails(checkin.offender, """{"contactPreference":{"requestedBy":"PRACT001","contactPreference":"PHONE"}}""")
    // a contact preference change doesn't reschedule by itself: re-run scheduling to show the date is still held
    upcomingCheckinService.scheduleNext(checkin.offender.id)
    assertThat(scheduledCheckins(checkin.offender).map { it.dueDate }).containsExactly(today.plusDays(10))

    // the practitioner sets the same schedule again: its next date is the cancelled one, so that's rebooked
    updateDetails(
      checkin.offender,
      """{"checkinSchedule":{"requestedBy":"PRACT001","firstCheckin":"${today.plusDays(3)}","checkinInterval":"WEEKLY","mode":"SCHEDULED"}}""",
    )
    assertThat(scheduledCheckins(checkin.offender).map { it.dueDate }).containsExactly(today.plusDays(3))
  }

  private fun scheduledCheckins(offender: Offender) = checkinRepository.findAllByOffenderAndStatus(offender, CheckinStatus.SCHEDULED)

  private fun updateDetails(offender: Offender, body: String) {
    webTestClient.post()
      .uri("/v2/offenders/{uuid}/update_details", offender.uuid)
      .headers(setAuthorisation(roles = listOf(UI_ROLE)))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(body)
      .exchange()
      .expectStatus().isOk
  }

  @Test
  fun `cancelling an upcoming check-in keeps its custom questions with it, not the next check-in`() {
    val checkin = saveCheckin(CheckinStatus.SCHEDULED, dueDate = LocalDate.now().plusDays(3))
    assignPendingQuestions(checkin)

    cancel(checkin.uuid).expectStatus().isOk

    assertThat(assignments(checkin)).containsExactly(checkin.id)
  }

  @Test
  fun `cancelling an open check-in leaves questions already assigned for the next check-in`() {
    val checkin = saveCheckin(CheckinStatus.CREATED)
    assignPendingQuestions(checkin)

    cancel(checkin.uuid).expectStatus().isOk

    assertThat(assignments(checkin)).containsExactly(null)
  }

  private fun assignPendingQuestions(checkin: OffenderCheckin) {
    jdbcTemplate.update("insert into question_list_assignment (question_list_id, offender_id) select id, ? from question_list where name = 'Default'", checkin.offender.id)
  }

  private fun assignments(checkin: OffenderCheckin): List<Long?> = jdbcTemplate.queryForList("select checkin_id from question_list_assignment where offender_id = ?", Long::class.javaObjectType, checkin.offender.id)

  @Test
  fun `a check-in whose status changed after it was read isn't cancelled`() {
    val checkin = saveCheckin(CheckinStatus.CREATED)
    jdbcTemplate.update("update offender_checkin_v2 set status = 'SUBMITTED' where id = ?", checkin.id)

    val event = transactionTemplate.execute {
      persistenceService.checkinCancellation(checkin, CheckinStatus.CREATED, cancellation()) { error("not expected") }
    }

    assertThat(event).isNull()
    val saved = checkinRepository.findByUuid(checkin.uuid).get()
    assertThat(saved.status).isEqualTo(CheckinStatus.SUBMITTED)
    assertThat(saved.cancelledBy).isNull()
    assertThat(jdbcTemplate.queryForObject("select count(*) from offender_event_log_v2", Int::class.java)).isZero()
  }

  @Test
  fun `cancelling doesn't overwrite a change made after the check-in was read`() {
    val checkin = saveCheckin(CheckinStatus.CREATED, linkStatus = CheckinLinkStatus.SENDING)
    jdbcTemplate.update("update offender_checkin_v2 set link_status = 'SENT' where id = ?", checkin.id)

    val event = transactionTemplate.execute {
      persistenceService.checkinCancellation(checkin, CheckinStatus.CREATED, cancellation()) { cancelledEvent(it) }
    }

    val saved = checkinRepository.findByUuid(checkin.uuid).get()
    assertThat(saved.status).isEqualTo(CheckinStatus.CANCELLED)
    assertThat(saved.linkStatus).isEqualTo(CheckinLinkStatus.SENT)
    assertThat(requireNotNull(event).linkStatus).isEqualTo(CheckinLinkStatus.SENT)
    assertThat(event.inviteSent).isTrue()
  }

  @Test
  fun `a submission read before the check-in was cancelled is refused`() {
    val checkin = saveCheckin(CheckinStatus.CREATED)
    cancel(checkin.uuid).expectStatus().isOk

    checkin.status = CheckinStatus.SUBMITTED
    checkin.submittedAt = Instant.now()
    val event = CheckinSubmittedEvent(checkin.id, checkin.offender.id, "PRACT001", checkin.dto(), checkin.offender.contactPreference)
    val error = assertThrows<ResponseStatusException> { persistenceService.checkinSubmission(checkin, event) }

    assertThat(error.statusCode.value()).isEqualTo(409)
    assertThat(checkinRepository.findByUuid(checkin.uuid).get().status).isEqualTo(CheckinStatus.CANCELLED)
  }

  @Test
  fun `a verification result read before the check-in was cancelled doesn't undo the cancellation`() {
    val checkin = saveCheckin(CheckinStatus.CREATED)
    cancel(checkin.uuid).expectStatus().isOk

    checkin.autoIdCheck = AutomatedIdVerificationResult.MATCH
    val error = assertThrows<ResponseStatusException> { persistenceService.saveOpenCheckin(checkin) }

    assertThat(error.statusCode.value()).isEqualTo(409)
    val saved = checkinRepository.findByUuid(checkin.uuid).get()
    assertThat(saved.status).isEqualTo(CheckinStatus.CANCELLED)
    assertThat(saved.cancelledBy).isEqualTo("PRACT_CANCEL")
    assertThat(saved.autoIdCheck).isNull()
  }

  private fun cancellation() = CheckinCancellation(cancelledBy = "PRACT_CANCEL", cancelledAt = Instant.now(), reason = "reason", sensitive = false)

  private fun cancelledEvent(checkin: OffenderCheckin) = CheckinCancelledEvent(
    checkinId = checkin.id,
    offenderId = checkin.offender.id,
    practitionerId = checkin.offender.practitionerId,
    checkin = checkin.dto(),
    offenderContactPreference = checkin.offender.contactPreference,
    previousStatus = CheckinStatus.CREATED,
    linkStatus = checkin.linkStatus,
  )

  private fun cancellationLog(checkin: OffenderCheckin) = jdbcTemplate.queryForMap(
    "select comment, sensitive, practitioner from offender_event_log_v2 where checkin = ? and log_entry_type = ?::log_entry_type_v2",
    checkin.id,
    LogEntryType.OFFENDER_CHECKIN_CANCELLED.name,
  ).let { LogRow(it["comment"] as String, it["sensitive"] as Boolean, it["practitioner"] as String) }

  private data class LogRow(val notes: String, val sensitive: Boolean, val practitioner: String)

  private fun cancel(uuid: UUID, reason: String = "No longer required", sensitive: Boolean = false): WebTestClient.ResponseSpec = webTestClient.post()
    .uri("/v2/offender_checkins/{uuid}/cancel", uuid)
    .headers(setAuthorisation(roles = listOf(UI_ROLE)))
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(body("PRACT_CANCEL", reason, sensitive))
    .exchange()

  private fun body(cancelledBy: String, reason: String, sensitive: Boolean) = """{"cancelledBy":"$cancelledBy","reason":"$reason","sensitive":$sensitive}"""

  private fun saveCheckin(
    status: CheckinStatus,
    dueDate: LocalDate = LocalDate.now(),
    firstCheckin: LocalDate? = offenderTemplate.firstCheckin,
    linkStatus: CheckinLinkStatus? = null,
    contactPreference: ContactPreference = offenderTemplate.contactPreference,
    mode: CheckinMode = CheckinMode.SCHEDULED,
  ): OffenderCheckin {
    val offender = offenderRepository.save(
      offenderTemplate.copy(uuid = UUID.randomUUID(), crn = "X${(100000..999999).random()}", firstCheckin = firstCheckin).toEntity()
        .also {
          it.scheduleUpdatedAt = Instant.now().minusSeconds(60)
          it.contactPreference = contactPreference
          it.mode = mode
          if (mode == CheckinMode.AD_HOC) {
            it.firstCheckin = null
            it.checkinInterval = null
          }
        },
    )
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(
      ContactDetails(crn = offender.crn, name = Name(forename = "John", surname = "Doe"), dateOfBirth = LocalDate.of(1980, 1, 1), mobile = "07700900000", email = "john@example.com"),
    )
    return checkinRepository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = status,
        dueDate = dueDate,
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
        linkStatus = linkStatus,
      ),
    )
  }

  companion object {
    private const val UI_ROLE = "ROLE_ESUPERVISION__ESUPERVISION_UI"
  }
}
