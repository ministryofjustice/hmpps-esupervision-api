package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.config.SurveyValueExpansionsConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinNoteResend
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinNoteResendRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.DomainEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.LogEntryType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

class CheckinNoteResendServiceTest {

  private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))

  private val resendRepository: CheckinNoteResendRepository = mock {
    on { saveAndFlush(any<CheckinNoteResend>()) } doAnswer { it.arguments[0] as CheckinNoteResend }
  }
  private val checkinRepository: OffenderCheckinRepository = mock()
  private val eventLogRepository: OffenderEventLogRepository = mock {
    on { saveAndFlush(any<OffenderEventLog>()) } doAnswer { it.arguments[0] as OffenderEventLog }
  }
  private val outboxItemRepository: OutboxItemRepository = mock()
  private val domainEventService: DomainEventService = mock {
    on { publishDomainEvent(any(), any(), any(), any(), anyOrNull(), anyOrNull()) } doReturn true
  }
  private val transactionTemplate: TransactionTemplate = mock {
    on { execute(any<TransactionCallback<OffenderEventLog>>()) } doAnswer {
      @Suppress("UNCHECKED_CAST")
      (it.arguments[0] as TransactionCallback<OffenderEventLog>).doInTransaction(mock())
    }
  }
  private val expansionsConfig = SurveyValueExpansionsConfig(
    mapOf("FEELING_GREAT" to "Feeling great"),
    mapOf(
      "mentalHealth" to "How they have been feeling",
      "callback" to "If they need us to contact them before their next appointment",
    ),
  )

  private val service = CheckinNoteResendService(
    resendRepository,
    checkinRepository,
    eventLogRepository,
    outboxItemRepository,
    domainEventService,
    expansionsConfig,
    transactionTemplate,
    clock,
  )

  private fun pending(vararg rows: CheckinNoteResend) {
    whenever(resendRepository.findBySentAtIsNull(any<Pageable>())).thenReturn(rows.toList())
  }

  @Test
  fun `happy path - creates annotation, publishes event, clears outbox and marks row sent`() {
    val checkin = createCheckin(surveyResponse = mapOf("mentalHealth" to "FEELING_GREAT"), sensitive = true)
    val row = CheckinNoteResend(checkin = checkin.uuid)
    pending(row)
    whenever(checkinRepository.findByUuid(checkin.uuid)).thenReturn(Optional.of(checkin))

    val processed = service.processPending(batchSize = 10, eventsPerSecond = 100.0)

    assertThat(processed).isEqualTo(1)

    val logCaptor = argumentCaptor<OffenderEventLog>()
    verify(eventLogRepository).saveAndFlush(logCaptor.capture())
    val logEntry = logCaptor.firstValue
    assertThat(logEntry.logEntryType).isEqualTo(LogEntryType.OFFENDER_CHECKIN_ANNOTATED)
    assertThat(logEntry.practitioner).isEqualTo("SYSTEM")
    assertThat(logEntry.sensitive).isTrue()
    assertThat(logEntry.checkin).isEqualTo(checkin.id)
    assertThat(logEntry.comment).contains("This comment was added due to a system issue.")
    assertThat(logEntry.comment).contains("answers for the check in submitted on 12 May 2026 at 3:30pm")
    assertThat(logEntry.comment).contains("Check in answers:")
    assertThat(logEntry.comment).contains("How they have been feeling: Feeling great")

    verify(domainEventService).publishDomainEvent(
      eq(DomainEventType.V2_CHECKIN_ANNOTATED),
      eq(logEntry.uuid),
      eq("X123456"),
      any(),
      isNull(),
      isNull(),
    )
    verify(outboxItemRepository).markAsSent(eq("CHECKIN_ANNOTATED"), eq(logEntry.id))

    assertThat(row.annotationUuid).isEqualTo(logEntry.uuid)
    assertThat(row.sentAt).isEqualTo(clock.instant())
    assertThat(row.notes).isEqualTo("SENT")
  }

  @Test
  fun `checkin not found - row marked skipped, nothing published`() {
    val row = CheckinNoteResend(checkin = UUID.randomUUID())
    pending(row)
    whenever(checkinRepository.findByUuid(row.checkin)).thenReturn(Optional.empty())

    service.processPending(batchSize = 10, eventsPerSecond = 100.0)

    verify(eventLogRepository, never()).saveAndFlush(any<OffenderEventLog>())
    verify(domainEventService, never()).publishDomainEvent(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    assertThat(row.sentAt).isEqualTo(clock.instant())
    assertThat(row.notes).isEqualTo("SKIPPED: checkin not found")
  }

  @Test
  fun `no survey response - row marked skipped, nothing published`() {
    val checkin = createCheckin(surveyResponse = null)
    val row = CheckinNoteResend(checkin = checkin.uuid)
    pending(row)
    whenever(checkinRepository.findByUuid(checkin.uuid)).thenReturn(Optional.of(checkin))

    service.processPending(batchSize = 10, eventsPerSecond = 100.0)

    verify(domainEventService, never()).publishDomainEvent(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    assertThat(row.notes).isEqualTo("SKIPPED: no survey response")
  }

  @Test
  fun `empty survey response - row marked skipped, nothing published`() {
    val checkin = createCheckin(surveyResponse = emptyMap())
    val row = CheckinNoteResend(checkin = checkin.uuid)
    pending(row)
    whenever(checkinRepository.findByUuid(checkin.uuid)).thenReturn(Optional.of(checkin))

    service.processPending(batchSize = 10, eventsPerSecond = 100.0)

    verify(domainEventService, never()).publishDomainEvent(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    assertThat(row.notes).isEqualTo("SKIPPED: no survey response")
  }

  @Test
  fun `retry with pre-existing annotation uuid - republishes same annotation without a new log row`() {
    val checkin = createCheckin(surveyResponse = mapOf("mentalHealth" to "FEELING_GREAT"))
    val annotationUuid = UUID.randomUUID()
    val existingLogEntry = OffenderEventLog(
      comment = "existing",
      sensitive = false,
      createdAt = clock.instant(),
      logEntryType = LogEntryType.OFFENDER_CHECKIN_ANNOTATED,
      practitioner = "SYSTEM",
      uuid = annotationUuid,
      checkin = checkin.id,
      offender = checkin.offender,
    )
    val row = CheckinNoteResend(checkin = checkin.uuid, annotationUuid = annotationUuid)
    pending(row)
    whenever(checkinRepository.findByUuid(checkin.uuid)).thenReturn(Optional.of(checkin))
    whenever(eventLogRepository.findByUuid(annotationUuid)).thenReturn(Optional.of(existingLogEntry))

    service.processPending(batchSize = 10, eventsPerSecond = 100.0)

    verify(eventLogRepository, never()).saveAndFlush(any<OffenderEventLog>())
    verify(domainEventService).publishDomainEvent(
      eq(DomainEventType.V2_CHECKIN_ANNOTATED),
      eq(annotationUuid),
      eq("X123456"),
      any(),
      isNull(),
      isNull(),
    )
    assertThat(row.sentAt).isEqualTo(clock.instant())
    assertThat(row.notes).isEqualTo("SENT")
  }

  @Test
  fun `publish failure leaves the row pending so the next run retries`() {
    val checkin = createCheckin(surveyResponse = mapOf("mentalHealth" to "FEELING_GREAT"))
    val row = CheckinNoteResend(checkin = checkin.uuid)
    pending(row)
    whenever(checkinRepository.findByUuid(checkin.uuid)).thenReturn(Optional.of(checkin))
    whenever(domainEventService.publishDomainEvent(any(), any(), any(), any(), anyOrNull(), anyOrNull())).thenReturn(false)

    service.processPending(batchSize = 10, eventsPerSecond = 100.0)

    // annotation is kept for the retry, but nothing is marked sent
    assertThat(row.annotationUuid).isNotNull()
    assertThat(row.sentAt).isNull()
    assertThat(row.notes).isEqualTo("PUBLISH FAILED: will retry")
    verify(outboxItemRepository, never()).markAsSent(any(), any())
  }

  @Test
  fun `failure on one row does not stop the rest of the batch`() {
    val failing = CheckinNoteResend(checkin = UUID.randomUUID())
    val checkin = createCheckin(surveyResponse = mapOf("mentalHealth" to "FEELING_GREAT"))
    val healthy = CheckinNoteResend(checkin = checkin.uuid)
    pending(failing, healthy)
    whenever(checkinRepository.findByUuid(failing.checkin)).thenThrow(RuntimeException("db error"))
    whenever(checkinRepository.findByUuid(checkin.uuid)).thenReturn(Optional.of(checkin))

    val processed = service.processPending(batchSize = 10, eventsPerSecond = 100.0)

    assertThat(processed).isEqualTo(1)
    assertThat(failing.sentAt).isNull()
    assertThat(healthy.sentAt).isEqualTo(clock.instant())
    assertThat(healthy.notes).isEqualTo("SENT")
  }

  private fun createOffender() = Offender(
    uuid = UUID.randomUUID(),
    crn = "X123456",
    practitionerId = "PRACT001",
    status = OffenderStatus.VERIFIED,
    firstCheckin = LocalDate.of(2026, 5, 12),
    checkinInterval = CheckinInterval.WEEKLY.duration,
    createdAt = Instant.parse("2026-05-01T10:00:00Z"),
    createdBy = "PRACT001",
    updatedAt = Instant.parse("2026-05-01T10:00:00Z"),
    contactPreference = ContactPreference.PHONE,
  )

  private fun createCheckin(
    surveyResponse: Map<String, Any>?,
    sensitive: Boolean = false,
  ) = OffenderCheckin(
    uuid = UUID.randomUUID(),
    offender = createOffender(),
    status = CheckinStatus.SUBMITTED,
    dueDate = LocalDate.of(2026, 5, 12),
    createdAt = Instant.parse("2026-05-11T10:00:00Z"),
    createdBy = "SYSTEM",
    // 14:30 UTC is 3:30pm in Europe/London (BST)
    submittedAt = Instant.parse("2026-05-12T14:30:00Z"),
    surveyResponse = surveyResponse,
    sensitive = sensitive,
  )
}
