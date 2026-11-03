package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinCancelledEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventDetailService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.LogEntryType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventNotes
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotificationService
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

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

  @MockitoBean
  private lateinit var ndiliusApiClient: INdiliusApiClient

  @MockitoBean
  private lateinit var notificationService: NotificationService

  @MockitoBean
  private lateinit var publisher: DomainEventPublisher

  @MockitoBean
  private lateinit var s3UploadService: S3UploadService

  private lateinit var featuresBefore: Set<Feature>

  @BeforeEach
  fun setUp() {
    featuresBefore = appConfig.enabledFeatures
    reset(notificationService)
  }

  @AfterEach
  fun tearDown() {
    ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore)
    jdbcTemplate.update("TRUNCATE TABLE checkin_delius_event_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE event_audit_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE outbox_items RESTART IDENTITY CASCADE")
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
    assertThat(event.firstValue.notifyOffender).isTrue()
  }

  @Test
  fun `cancelling an upcoming check-in doesn't tell the offender`() {
    val checkin = saveCheckin(CheckinStatus.SCHEDULED, dueDate = LocalDate.now().plusDays(3))

    cancel(checkin.uuid).expectStatus().isOk

    val event = argumentCaptor<CheckinCancelledEvent>()
    verify(notificationService, timeout(2000)).sendCheckinCancelledNotifications(event.capture())
    assertThat(event.firstValue.notifyOffender).isFalse()
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
    assertThat(detail.notes).isEqualTo("Reason: Attending in person instead")
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

  private fun saveCheckin(status: CheckinStatus, dueDate: LocalDate = LocalDate.now()): OffenderCheckin {
    val offender = offenderRepository.save(offenderTemplate.copy(uuid = UUID.randomUUID(), crn = "X${(100000..999999).random()}").toEntity())
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(
      ContactDetails(crn = offender.crn, name = Name(forename = "John", surname = "Doe"), dateOfBirth = LocalDate.of(1980, 1, 1), mobile = "07700900000"),
    )
    return checkinRepository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = status,
        dueDate = dueDate,
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
      ),
    )
  }

  companion object {
    private const val UI_ROLE = "ROLE_ESUPERVISION__ESUPERVISION_UI"
  }
}
