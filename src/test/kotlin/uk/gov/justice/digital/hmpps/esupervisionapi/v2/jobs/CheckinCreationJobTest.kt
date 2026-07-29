package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PartialCheckinCreatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

class CheckinCreationJobTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-10T09:00:00Z"), ZoneId.of("UTC"))
  private val offenderRepository: OffenderRepository = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val checkinCreationService: CheckinCreationService = mock()

  private val deactivationService: OffenderDeactivationService = mock()
  private val jobLogRepository: JobLogRepository = mock {
    on { saveAndFlush(any<JobLog>()) } doAnswer { it.arguments[0] as JobLog }
  }
  private val entityManager: EntityManager = mock()

  private val job = CheckinCreationJob(
    clock,
    offenderRepository,
    ndiliusApiClient,
    checkinCreationService,
    deactivationService,
    jobLogRepository,
    entityManager,
    chunkSize = 100,
    Duration.ofDays(3),
  )

  private val anEvent = Event(number = 1L, mainOffence = CodedDescription("X", "An offence"), sentence = null)

  @Test
  fun `eligible offender - creates checkin, notifies, does not deactivate`() {
    val offender = offender("X000001")
    stubEligible(listOf(offender), mapOf(offender.crn to details(offender.crn, events = listOf(anEvent))))

    job.process()

    verify(checkinCreationService).prepareCheckinForOffender(eq(offender), any())
    verify(checkinCreationService).createCheckins(any())
    verify(deactivationService, never()).deactivateOffender(any(), any(), any(), any(), any())
  }

  @Test
  fun `ineligible (contact suspended) - deactivates and creates no checkin`() {
    val offender = offender("X000002")
    stubEligible(listOf(offender), mapOf(offender.crn to details(offender.crn, events = listOf(anEvent), suspended = true)))

    job.process()

    verify(deactivationService).deactivateOffender(eq(offender), any(), any(), any(), eq(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED))
    verify(checkinCreationService, never()).prepareCheckinForOffender(any(), any())
  }

  @Test
  fun `ineligible (explicitly no active events) - deactivates`() {
    val offender = offender("X000003")
    stubEligible(listOf(offender), mapOf(offender.crn to details(offender.crn, events = emptyList())))

    job.process()

    verify(deactivationService).deactivateOffender(eq(offender), any(), any(), any(), eq(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS))
    verify(checkinCreationService, never()).prepareCheckinForOffender(any(), any())
  }

  @Test
  fun `a deactivation failure for one offender does not abort the rest of the run`() {
    val ineligible = offender("X000005")
    val eligible = offender("X000006")
    stubEligible(
      listOf(ineligible, eligible),
      mapOf(
        ineligible.crn to details(ineligible.crn, events = emptyList()),
        eligible.crn to details(eligible.crn, events = listOf(anEvent)),
      ),
    )
    whenever(deactivationService.deactivateOffender(any(), any(), any(), any(), any()))
      .thenThrow(RuntimeException("boom"))

    job.process()

    // the failure on the ineligible offender is isolated; the eligible offender is still processed.
    // NB: V2BaseEntity.equals() is id-based and unsaved test offenders share id=0, so match by
    // reference identity (same()) rather than eq() to assert the correct offender each time.
    verify(deactivationService).deactivateOffender(same(ineligible), any(), any(), any(), any())
    verify(checkinCreationService, times(1)).prepareCheckinForOffender(any(), any())
  }

  @Test
  fun `missing contact details - offender is neither deactivated nor given a checkin`() {
    val offender = offender("X000007")
    // contact details map is empty (NDelius returned nothing for this CRN)
    stubEligible(listOf(offender), emptyMap())

    job.process()

    verify(deactivationService, never()).deactivateOffender(any(), any(), any(), any(), any())
    verify(checkinCreationService, never()).prepareCheckinForOffender(any(), any())
  }

  private fun stubEligible(offenders: List<Offender>, detailsByCrn: Map<String, ContactDetails>) {
    for (i in 0 until offenders.size) {
      ReflectionTestUtils.setField(offenders[i], "id", i.toLong())
      whenever(offenderRepository.findById(eq(i.toLong()))).thenReturn(Optional.of(offenders[i]))
    }

    data class CheckinCreationInfo(
      override val id: Long,
      override val crn: CRN,
      override val practitionerId: ExternalUserId,
      override val contactPreference: ContactPreference,
      override val currentEvent: Long?,
    ) : OffenderRepository.IOffenderCheckinCreationInfo
    whenever(offenderRepository.findEligibleForCheckinCreation(any(), any(), any(), anyOrNull()))
      .thenReturn(offenders.map { CheckinCreationInfo(it.id, it.crn, it.practitionerId, it.contactPreference, it.currentEvent) })
    whenever(offenderRepository.getReferenceById(any())).thenReturn(mock<Offender>())
    whenever(ndiliusApiClient.getContactDetailsForMultiple(any())).thenReturn(detailsByCrn.values.toList())
    whenever(checkinCreationService.prepareCheckinForOffender(any(), any())).thenAnswer { arg ->
      val offender = arg.getArgument<Offender>(0)
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.CREATED,
        dueDate = clock.today(),
        createdAt = clock.instant(),
        createdBy = "SYSTEM",
      )
    }
    whenever(checkinCreationService.createCheckins(any())).thenAnswer { it.getArgument<List<Pair<OffenderCheckin, PartialCheckinCreatedEvent>>>(0) }
    whenever(deactivationService.deactivateOffender(any(), any(), any(), any(), any())).thenAnswer { it.getArgument<Offender>(0) }
  }

  private fun details(crn: String, events: List<Event> = emptyList(), suspended: Boolean = false) = ContactDetails(crn = crn, name = Name("John", "Doe"), events = events, contactSuspended = suspended, dateOfBirth = LocalDate.of(1980, 1, 1))

  private fun offender(crn: String) = Offender(
    uuid = UUID.randomUUID(),
    crn = crn,
    practitionerId = "PRACT001",
    status = OffenderStatus.VERIFIED,
    firstCheckin = LocalDate.now(clock),
    checkinInterval = CheckinInterval.WEEKLY.duration,
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
    updatedAt = clock.instant(),
    contactPreference = ContactPreference.EMAIL,
  )
}
