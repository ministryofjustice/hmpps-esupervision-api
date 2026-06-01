package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationV2Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.stream.Stream

class V2CheckinCreationJobTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-10T09:00:00Z"), ZoneId.of("UTC"))
  private val offenderRepository: OffenderV2Repository = mock()
  private val checkinRepository: OffenderCheckinV2Repository = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val checkinCreationService: CheckinCreationService = mock()
  private val notificationService: NotificationV2Service = mock()
  private val deactivationService: OffenderDeactivationV2Service = mock()
  private val jobLogRepository: JobLogV2Repository = mock {
    on { saveAndFlush(any<JobLogV2>()) } doAnswer { it.arguments[0] as JobLogV2 }
  }
  private val entityManager: EntityManager = mock()

  private val job = V2CheckinCreationJob(
    clock,
    offenderRepository,
    checkinRepository,
    ndiliusApiClient,
    checkinCreationService,
    notificationService,
    deactivationService,
    jobLogRepository,
    entityManager,
    chunkSize = 100,
  )

  private val anEvent = Event(number = 1L, mainOffence = CodedDescription("X", "An offence"), sentence = null)

  @Test
  fun `eligible offender - creates checkin, notifies, does not deactivate`() {
    val offender = offender("X000001")
    stubEligible(listOf(offender), mapOf(offender.crn to details(offender.crn, events = listOf(anEvent))))

    job.process()

    verify(checkinCreationService).prepareCheckinForOffender(eq(offender), any())
    verify(checkinCreationService).batchCreateCheckins(any())
    verify(notificationService).sendCheckinCreatedNotifications(any(), any())
    verify(deactivationService, never()).deactivateOffender(any(), any(), any(), any())
  }

  @Test
  fun `ineligible (contact suspended) - deactivates and creates no checkin`() {
    val offender = offender("X000002")
    stubEligible(listOf(offender), mapOf(offender.crn to details(offender.crn, events = listOf(anEvent), suspended = true)))

    job.process()

    verify(deactivationService).deactivateOffender(eq(offender), any(), any(), any())
    verify(checkinCreationService, never()).prepareCheckinForOffender(any(), any())
    verify(notificationService, never()).sendCheckinCreatedNotifications(any(), any())
  }

  @Test
  fun `ineligible (explicitly no active events) - deactivates`() {
    val offender = offender("X000003")
    stubEligible(listOf(offender), mapOf(offender.crn to details(offender.crn, events = emptyList())))

    job.process()

    verify(deactivationService).deactivateOffender(eq(offender), any(), any(), any())
    verify(checkinCreationService, never()).prepareCheckinForOffender(any(), any())
  }

  @Test
  fun `absent events list is indeterminate - treated as eligible, not deactivated`() {
    val offender = offender("X000004")
    stubEligible(listOf(offender), mapOf(offender.crn to details(offender.crn, events = null)))

    job.process()

    verify(checkinCreationService).prepareCheckinForOffender(eq(offender), any())
    verify(deactivationService, never()).deactivateOffender(any(), any(), any(), any())
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
    whenever(deactivationService.deactivateOffender(any(), any(), any(), any()))
      .thenThrow(RuntimeException("boom"))

    job.process()

    // the failure on the ineligible offender is isolated; the eligible offender is still processed.
    // NB: V2BaseEntity.equals() is id-based and unsaved test offenders share id=0, so match by
    // reference identity (same()) rather than eq() to assert the correct offender each time.
    verify(deactivationService).deactivateOffender(same(ineligible), any(), any(), any())
    verify(checkinCreationService).prepareCheckinForOffender(same(eligible), any())
    verify(notificationService).sendCheckinCreatedNotifications(any(), any())
  }

  @Test
  fun `missing contact details - offender is neither deactivated nor given a checkin`() {
    val offender = offender("X000007")
    // contact details map is empty (NDelius returned nothing for this CRN)
    stubEligible(listOf(offender), emptyMap())

    job.process()

    verify(deactivationService, never()).deactivateOffender(any(), any(), any(), any())
    verify(checkinCreationService, never()).prepareCheckinForOffender(any(), any())
  }

  private fun stubEligible(offenders: List<OffenderV2>, detailsByCrn: Map<String, ContactDetails>) {
    whenever(offenderRepository.findEligibleForCheckinCreation(any(), any())).thenReturn(Stream.of(*offenders.toTypedArray()))
    whenever(ndiliusApiClient.getContactDetailsForMultiple(any())).thenReturn(detailsByCrn.values.toList())
    whenever(checkinCreationService.prepareCheckinForOffender(any(), any())).thenReturn(mock<OffenderCheckinV2>())
    whenever(checkinCreationService.batchCreateCheckins(any())).thenAnswer { it.getArgument(0) }
    whenever(deactivationService.deactivateOffender(any(), any(), any(), any())).thenAnswer { it.getArgument<OffenderV2>(0) }
  }

  private fun details(crn: String, events: List<Event>? = null, suspended: Boolean = false) = ContactDetails(crn = crn, name = Name("John", "Doe"), events = events, contactSuspended = suspended)

  private fun offender(crn: String) = OffenderV2(
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
