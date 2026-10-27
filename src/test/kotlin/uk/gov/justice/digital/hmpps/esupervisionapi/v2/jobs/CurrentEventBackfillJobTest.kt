package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class CurrentEventBackfillJobTest {

  private val clock = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneId.of("UTC"))
  private val offenderRepository: OffenderRepository = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val offenderDeactivationService: OffenderDeactivationService = mock()
  private val jobLogRepository: JobLogRepository = mock {
    on { saveAndFlush(any<JobLog>()) } doAnswer { it.arguments[0] as JobLog }
  }

  @Test
  fun `selects the event with the latest expected end date and persists it`() {
    val offender = offender("X123456")
    stubCandidates(offender)
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(offender.crn))).thenReturn(
      listOf(contactDetails(offender.crn, listOf(event(1L, "2027-01-01"), event(2L, "2028-01-01")))),
    )

    job(dryRun = false).process()

    assertThat(offender.currentEvent).isEqualTo(2L)
    verify(offenderRepository).save(offender)
    verify(offenderDeactivationService, never()).deactivateOffender(any(), any(), any(), any(), any())
  }

  @Test
  fun `dry run reports candidates without updating or deactivating them`() {
    val offender = offender("X123456")
    stubCandidates(offender)
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(offender.crn))).thenReturn(
      listOf(contactDetails(offender.crn, listOf(event(1L, "2027-01-01")))),
    )

    job(dryRun = true).process()

    assertThat(offender.currentEvent).isNull()
    verify(offenderRepository, never()).save(any())
    verify(offenderDeactivationService, never()).deactivateOffender(any(), any(), any(), any(), any())
  }

  @Test
  fun `deactivates a verified offender when Delius returns no active sentence`() {
    val offender = offender("X123456")
    stubCandidates(offender)
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(offender.crn))).thenReturn(
      listOf(contactDetails(offender.crn, emptyList())),
    )

    job(dryRun = false).process()

    verify(offenderDeactivationService).deactivateOffender(
      eq(offender),
      any(),
      any(),
      any(),
      eq(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS),
    )
    verify(offenderRepository, never()).save(any())
  }

  @Test
  fun `does not guess between multiple sentences when an expected end date is missing`() {
    val offender = offender("X123456")
    stubCandidates(offender)
    val undatedEvent = event(2L, "2028-01-01").copy(
      sentence = Event.Sentence(
        date = LocalDate.of(2026, 1, 1),
        description = "Sentence with no expected end date",
        expectedEndDate = null,
      ),
    )
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(offender.crn))).thenReturn(
      listOf(contactDetails(offender.crn, listOf(event(1L, "2027-01-01"), undatedEvent))),
    )

    assertThrows(IllegalStateException::class.java) { job(dryRun = false).process() }

    assertThat(offender.currentEvent).isNull()
    verify(offenderRepository, never()).save(any())
    verify(offenderDeactivationService, never()).deactivateOffender(any(), any(), any(), any(), any())
  }

  @Test
  fun `does not deactivate when batch response omits a CRN and fails the job`() {
    val offender = offender("X123456")
    stubCandidates(offender)
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(offender.crn))).thenReturn(emptyList())

    assertThrows(IllegalStateException::class.java) { job(dryRun = false).process() }

    verify(offenderDeactivationService, never()).deactivateOffender(any(), any(), any(), any(), any())
    verify(offenderRepository, never()).save(any())
  }

  private fun job(dryRun: Boolean) = CurrentEventBackfillJob(
    clock = clock,
    offenderRepository = offenderRepository,
    ndiliusApiClient = ndiliusApiClient,
    offenderDeactivationService = offenderDeactivationService,
    jobLogRepository = jobLogRepository,
    chunkSize = 10,
    dryRun = dryRun,
  )

  private fun stubCandidates(offender: Offender) {
    whenever(
      offenderRepository.findVerifiedWithoutCurrentEventAfterId(any(), any(), any<Pageable>()),
    ).thenReturn(listOf(offender), emptyList())
  }

  private fun offender(crn: String) = Offender(
    uuid = UUID.randomUUID(),
    crn = crn,
    practitionerId = "PRACT001",
    status = OffenderStatus.VERIFIED,
    firstCheckin = LocalDate.of(2026, 11, 1),
    checkinInterval = CheckinInterval.WEEKLY.duration,
    createdAt = clock.instant(),
    createdBy = "PRACT001",
    updatedAt = clock.instant(),
    contactPreference = ContactPreference.EMAIL,
  )

  private fun contactDetails(crn: String, events: List<Event>) = ContactDetails(
    crn = crn,
    name = uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("First", "Last"),
    dateOfBirth = LocalDate.of(1980, 1, 1),
    events = events,
  )

  private fun event(number: Long, expectedEndDate: String) = Event(
    number = number,
    mainOffence = CodedDescription("OFFENCE", "Offence"),
    sentence = Event.Sentence(
      date = LocalDate.of(2026, 1, 1),
      description = "Sentence",
      expectedEndDate = LocalDate.parse(expectedEndDate),
      length = null,
      lengthUnit = null,
    ),
  )
}
