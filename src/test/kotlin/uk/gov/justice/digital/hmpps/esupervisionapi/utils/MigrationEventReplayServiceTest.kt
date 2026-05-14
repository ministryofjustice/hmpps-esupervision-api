package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.DomainEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NdiliusBatchFetchException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SetupEventBackfillV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SetupEventBackfillV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.AdditionalInformation
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.persistence.V2BaseEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

class MigrationEventReplayServiceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-03T10:00:00Z"), ZoneId.of("UTC"))
  private val checkinRepository: OffenderCheckinV2Repository = mock()
  private val offenderRepository: OffenderV2Repository = mock()
  private val offenderSetupRepository: OffenderSetupV2Repository = mock()
  private val backfillRepository: SetupEventBackfillV2Repository = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val domainEventService: DomainEventService = mock()
  private val eventAuditLogRepository: EventAuditV2Repository = mock()

  private lateinit var service: MigrationEventReplayService

  @BeforeEach
  fun setUp() {
    service = MigrationEventReplayService(
      checkinRepository,
      offenderRepository,
      offenderSetupRepository,
      backfillRepository,
      ndiliusApiClient,
      domainEventService,
      eventAuditLogRepository,
      clock,
    )
  }

  // ---------- Phase 1: createMissingSetupV2Rows ----------

  @Test
  fun `createMissingSetupV2Rows creates setup and marks flag when no setup exists`() {
    val offender = newOffender(id = 100L)
    val row = SetupEventBackfillV2(offenderId = 100L, createdAt = clock.instant())

    whenever(backfillRepository.findPendingSetupRowCreation(PageRequest.of(0, 50)))
      .thenReturn(listOf(row))
      .thenReturn(emptyList())
    whenever(offenderRepository.findById(100L)).thenReturn(Optional.of(offender))
    whenever(offenderSetupRepository.findByOffender(offender)).thenReturn(Optional.empty())

    val created = service.createMissingSetupV2Rows(batchSize = 50)

    assertThat(created).isEqualTo(1)
    assertThat(row.setupRowCreated).isTrue()

    val captor = argumentCaptor<OffenderSetupV2>()
    verify(offenderSetupRepository).save(captor.capture())
    val saved = captor.firstValue
    assertThat(saved.offender).isEqualTo(offender)
    assertThat(saved.practitionerId).isEqualTo(offender.practitionerId)
    assertThat(saved.createdAt).isEqualTo(offender.createdAt)
    assertThat(saved.startedAt).isEqualTo(offender.createdAt)
    assertThat(saved.setupCounter).isEqualTo(1)
  }

  @Test
  fun `createMissingSetupV2Rows is idempotent when setup already exists`() {
    val offender = newOffender(id = 100L)
    val existingSetup = OffenderSetupV2(
      uuid = UUID.randomUUID(),
      offender = offender,
      practitionerId = offender.practitionerId,
      createdAt = offender.createdAt,
      startedAt = null,
    )
    val row = SetupEventBackfillV2(offenderId = 100L, createdAt = clock.instant())

    whenever(backfillRepository.findPendingSetupRowCreation(any()))
      .thenReturn(listOf(row))
      .thenReturn(emptyList())
    whenever(offenderRepository.findById(100L)).thenReturn(Optional.of(offender))
    whenever(offenderSetupRepository.findByOffender(offender)).thenReturn(Optional.of(existingSetup))

    val created = service.createMissingSetupV2Rows(batchSize = 50)

    assertThat(created).isEqualTo(0)
    assertThat(row.setupRowCreated).isTrue()
    verify(offenderSetupRepository, never()).save(any())
  }

  @Test
  fun `createMissingSetupV2Rows flags row when offender is missing`() {
    val row = SetupEventBackfillV2(offenderId = 999L, createdAt = clock.instant())

    whenever(backfillRepository.findPendingSetupRowCreation(any()))
      .thenReturn(listOf(row))
      .thenReturn(emptyList())
    whenever(offenderRepository.findById(999L)).thenReturn(Optional.empty())

    val created = service.createMissingSetupV2Rows(batchSize = 50)

    assertThat(created).isEqualTo(0)
    assertThat(row.setupRowCreated).isTrue()
    verify(offenderSetupRepository, never()).save(any())
  }

  // ---------- Phase 2: replayActiveOffenderSetupEvents ----------

  @Test
  fun `replayActiveOffenderSetupEvents publishes V2_SETUP_COMPLETED with correct payload`() {
    val offender = newOffender(id = 100L)
    val setup = OffenderSetupV2(
      uuid = UUID.randomUUID(),
      offender = offender,
      practitionerId = offender.practitionerId,
      createdAt = offender.createdAt,
      startedAt = offender.createdAt,
    )
    val row = SetupEventBackfillV2(offenderId = 100L, createdAt = clock.instant())
    val contactDetails = ContactDetails(
      crn = offender.crn,
      name = Name("John", "Smith"),
      events = listOf(Event(number = 7L, mainOffence = CodedDescription("OFF01", "Test"), sentence = null)),
    )

    whenever(backfillRepository.findPendingEventSend(any()))
      .thenReturn(listOf(row))
      .thenReturn(emptyList())
    whenever(offenderRepository.findAllById(listOf(100L))).thenReturn(listOf(offender))
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(offender.crn))).thenReturn(listOf(contactDetails))
    whenever(offenderSetupRepository.findByOffender(offender)).thenReturn(Optional.of(setup))

    val sent = service.replayActiveOffenderSetupEvents(batchSize = 50)

    assertThat(sent).isEqualTo(1)
    assertThat(row.eventSent).isTrue()
    assertThat(row.eventSentAt).isEqualTo(clock.instant())

    verify(domainEventService).publishDomainEvent(
      eventType = eq(DomainEventType.V2_SETUP_COMPLETED),
      uuid = eq(offender.uuid),
      crn = eq(offender.crn),
      description = eq("Practitioner completed setup for offender ${offender.crn}"),
      occurredAt = eq(offender.createdAt.atZone(clock.zone)),
      additionalInformation = eq(AdditionalInformation(eventNumber = 7L, setupId = setup.setupId())),
    )
  }

  @Test
  fun `replayActiveOffenderSetupEvents skips publish when Delius has no active events`() {
    val offender = newOffender(id = 100L)
    val row = SetupEventBackfillV2(offenderId = 100L, createdAt = clock.instant())
    val contactDetails = ContactDetails(crn = offender.crn, name = Name("John", "Smith"), events = emptyList())

    whenever(backfillRepository.findPendingEventSend(any()))
      .thenReturn(listOf(row))
      .thenReturn(emptyList())
    whenever(offenderRepository.findAllById(listOf(100L))).thenReturn(listOf(offender))
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(offender.crn))).thenReturn(listOf(contactDetails))

    val sent = service.replayActiveOffenderSetupEvents(batchSize = 50)

    assertThat(sent).isEqualTo(0)
    assertThat(row.eventSent).isTrue()
    verify(domainEventService, never()).publishDomainEvent(any(), any(), any(), any(), any(), any())
    verify(offenderSetupRepository, never()).findByOffender(any())
  }

  @Test
  fun `replayActiveOffenderSetupEvents skips publish when Delius returns no contact details`() {
    val offender = newOffender(id = 100L)
    val row = SetupEventBackfillV2(offenderId = 100L, createdAt = clock.instant())

    whenever(backfillRepository.findPendingEventSend(any()))
      .thenReturn(listOf(row))
      .thenReturn(emptyList())
    whenever(offenderRepository.findAllById(listOf(100L))).thenReturn(listOf(offender))
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(offender.crn))).thenReturn(emptyList())

    val sent = service.replayActiveOffenderSetupEvents(batchSize = 50)

    assertThat(sent).isEqualTo(0)
    assertThat(row.eventSent).isTrue()
    verify(domainEventService, never()).publishDomainEvent(any(), any(), any(), any(), any(), any())
  }

  @Test
  fun `replayActiveOffenderSetupEvents does not flag rows when Delius batch call fails`() {
    val offender = newOffender(id = 100L)
    val row = SetupEventBackfillV2(offenderId = 100L, createdAt = clock.instant())

    whenever(backfillRepository.findPendingEventSend(any()))
      .thenReturn(listOf(row))
      .thenReturn(emptyList())
    whenever(offenderRepository.findAllById(listOf(100L))).thenReturn(listOf(offender))
    doThrow(NdiliusBatchFetchException(listOf(offender.crn), "boom", RuntimeException("boom")))
      .whenever(ndiliusApiClient).getContactDetailsForMultiple(any())

    val sent = service.replayActiveOffenderSetupEvents(batchSize = 50)

    assertThat(sent).isEqualTo(0)
    assertThat(row.eventSent).isFalse()
    verify(domainEventService, never()).publishDomainEvent(any(), any(), any(), any(), any(), any())
  }

  @Test
  fun `replayActiveOffenderSetupEvents leaves row pending when setup row is missing`() {
    val offender = newOffender(id = 100L)
    val row = SetupEventBackfillV2(offenderId = 100L, createdAt = clock.instant())
    val contactDetails = ContactDetails(
      crn = offender.crn,
      name = Name("John", "Smith"),
      events = listOf(Event(number = 7L, mainOffence = CodedDescription("OFF01", "Test"), sentence = null)),
    )

    whenever(backfillRepository.findPendingEventSend(any()))
      .thenReturn(listOf(row))
      .thenReturn(emptyList())
    whenever(offenderRepository.findAllById(listOf(100L))).thenReturn(listOf(offender))
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(offender.crn))).thenReturn(listOf(contactDetails))
    whenever(offenderSetupRepository.findByOffender(offender)).thenReturn(Optional.empty())

    val sent = service.replayActiveOffenderSetupEvents(batchSize = 50)

    assertThat(sent).isEqualTo(0)
    assertThat(row.eventSent).isFalse()
    verify(domainEventService, never()).publishDomainEvent(any(), any(), any(), any(), any(), any())
  }

  // ---------- helpers ----------

  private fun newOffender(id: Long, crn: String = "X123456"): OffenderV2 {
    val offender = OffenderV2(
      uuid = UUID.randomUUID(),
      crn = crn,
      practitionerId = "PRACT001",
      status = OffenderStatus.VERIFIED,
      firstCheckin = LocalDate.now(clock),
      checkinInterval = CheckinInterval.WEEKLY.duration,
      createdAt = clock.instant().minusSeconds(7 * 24 * 3600),
      createdBy = "PRACT001",
      updatedAt = clock.instant(),
      contactPreference = ContactPreference.EMAIL,
    )
    setEntityId(offender, id)
    return offender
  }

  private fun setEntityId(entity: V2BaseEntity, id: Long) {
    val field = V2BaseEntity::class.java.getDeclaredField("id")
    field.isAccessible = true
    field.setLong(entity, id)
  }
}
