package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
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

class V2CheckinReminderJobTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-10T09:00:00Z"), ZoneId.of("UTC"))
  private val checkinRepository: OffenderCheckinV2Repository = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val notificationService: NotificationV2Service = mock()
  private val deactivationService: OffenderDeactivationV2Service = mock()
  private val jobLogRepository: JobLogV2Repository = mock {
    on { saveAndFlush(any<JobLogV2>()) } doAnswer { it.arguments[0] as JobLogV2 }
  }
  private val transactionTemplate: TransactionTemplate = mock {
    on { execute<Any?>(any()) } doAnswer {
      (it.getArgument(0) as TransactionCallback<Any?>).doInTransaction(mock())
    }
  }
  private val eventAuditService: EventAuditV2Service = mock()
  private val genericNotificationV2Repository: GenericNotificationV2Repository = mock()

  private val job = V2CheckinReminderJob(
    clock,
    checkinRepository,
    ndiliusApiClient,
    notificationService,
    deactivationService,
    jobLogRepository,
    transactionTemplate,
    eventAuditService,
    genericNotificationV2Repository,
  )

  private val anEvent = Event(number = 1L, mainOffence = CodedDescription("X", "An offence"), sentence = null)

  @Test
  fun `eligible checkin is reminded and not deactivated`() {
    val checkin = checkin("X000001")
    val cd = details("X000001", events = listOf(anEvent))
    stub(listOf(checkin), listOf(cd))

    job.process()

    verify(notificationService).sendCheckinReminderNotifications(same(checkin), eq(cd))
    verify(deactivationService, never()).deactivateOffender(any(), any(), any(), any(), any())
  }

  @Test
  fun `ineligible checkin is deactivated, not reminded, and excluded from the reminder audit`() {
    val eligible = checkin("X000001")
    val ineligible = checkin("X000002")
    val missing = checkin("X000003")
    val eligibleCd = details("X000001", events = listOf(anEvent))
    val ineligibleCd = details("X000002", suspended = true)
    // X000003 deliberately absent from the NDelius response
    stub(listOf(eligible, ineligible, missing), listOf(eligibleCd, ineligibleCd))

    job.process()

    // NB: V2BaseEntity.equals() is id-based and all unsaved test entities share id=0, so eq() cannot
    // tell the instances apart - match by reference identity (same()/===) instead.
    verify(deactivationService).deactivateOffender(same(ineligible.offender), any(), any(), any(), eq(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED))
    verify(notificationService).sendCheckinReminderNotifications(same(eligible), eq(eligibleCd))
    verify(notificationService, never()).sendCheckinReminderNotifications(same(ineligible), any())

    // the deactivated checkin must NOT appear in any CHECKIN_REMINDER audit (it was cancelled)
    val captor = argumentCaptor<Iterable<Pair<OffenderCheckinV2, ContactDetails?>>>()
    verify(eventAuditService, org.mockito.kotlin.atLeastOnce()).recordCheckinReminded(captor.capture())
    val allAudited = captor.allValues.flatMap { it.toList() }.map { it.first }
    assertThat(allAudited.any { it === eligible }).isTrue()
    assertThat(allAudited.any { it === missing }).isTrue()
    assertThat(allAudited.none { it === ineligible }).isTrue()
  }

  private fun stub(checkins: List<OffenderCheckinV2>, details: List<ContactDetails>) {
    whenever(checkinRepository.findEligibleForReminder(any(), any(), any())).thenReturn(Stream.of(*checkins.toTypedArray()))
    whenever(ndiliusApiClient.getContactDetailsForMultiple(any())).thenReturn(details)
    whenever(deactivationService.deactivateOffender(any(), any(), any(), any(), any())).thenAnswer { it.getArgument<OffenderV2>(0) }
  }

  private fun details(crn: String, events: List<Event> = emptyList(), suspended: Boolean = false) = ContactDetails(crn = crn, name = Name("John", "Doe"), events = events, contactSuspended = suspended)

  private fun checkin(crn: String) = OffenderCheckinV2(
    uuid = UUID.randomUUID(),
    offender = offender(crn),
    status = uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status.CREATED,
    dueDate = LocalDate.now(clock).minusDays(2),
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
  )

  private fun offender(crn: String) = OffenderV2(
    uuid = UUID.randomUUID(),
    crn = crn,
    practitionerId = "PRACT001",
    status = OffenderStatus.VERIFIED,
    firstCheckin = LocalDate.now(clock).minusDays(2),
    checkinInterval = CheckinInterval.WEEKLY.duration,
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
    updatedAt = clock.instant(),
    contactPreference = ContactPreference.EMAIL,
  )
}
