package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
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
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotificationService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.stream.Stream

class CheckinExpiryJobTest {

  private val clock = Clock.fixed(Instant.parse("2026-11-02T09:00:00Z"), ZoneId.of("UTC"))
  private val checkinRepository: OffenderCheckinRepository = mock {
    on { findEligibleForExpiry(any()) } doAnswer { Stream.of(checkin()) }
  }
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val notificationService: NotificationService = mock()
  private val jobLogRepository: JobLogRepository = mock {
    on { saveAndFlush(any<JobLog>()) } doAnswer { it.arguments[0] as JobLog }
  }
  private val transactionTemplate: TransactionTemplate = mock {
    on { execute<Any?>(any()) } doAnswer {
      (it.getArgument(0) as TransactionCallback<Any?>).doInTransaction(mock())
    }
  }
  private val checkinDeliusEventService: CheckinDeliusEventService = mock {
    on { enabled() } doAnswer { true }
  }
  private val eventAuditService: EventAuditService = mock()
  private val appConfig: AppConfig = mock {
    on { esup2305DeliusEventSelection } doAnswer { true }
  }
  private val job = CheckinExpiryJob(
    clock = clock,
    checkinRepository = checkinRepository,
    ndiliusApiClient = ndiliusApiClient,
    notificationService = notificationService,
    jobLogRepository = jobLogRepository,
    transactionTemplate = transactionTemplate,
    gracePeriodDays = 3,
    eventAuditService = eventAuditService,
    checkinDeliusEventService = checkinDeliusEventService,
    appConfig = appConfig,
  )

  @Test
  fun `expiry relay row contains the resolved event when no selection is saved`() {
    val checkin = checkin()
    whenever(checkinRepository.findEligibleForExpiry(any())).thenAnswer { Stream.of(checkin) }
    val details = ContactDetails(
      crn = checkin.offender.crn,
      name = Name("First", "Last"),
      dateOfBirth = LocalDate.of(1980, 1, 1),
      events = listOf(
        event(1L, LocalDate.of(2027, 1, 1)),
        event(2L, LocalDate.of(2028, 1, 1)),
      ),
    )
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(checkin.offender.crn))).thenReturn(listOf(details))
    whenever(checkinRepository.updateStatusForCheckinId(checkin.id, CheckinStatus.CREATED, CheckinStatus.EXPIRED)).thenReturn(1)

    job.process()

    verify(checkinDeliusEventService).recordCheckinUpdate(
      eq(uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType.V2_CHECKIN_EXPIRED),
      eq(checkin),
      detailId = eq(checkin.uuid),
      eventNumber = eq(2L),
    )
    verify(notificationService).sendCheckinExpiredNotifications(checkin, details)
  }

  @Test
  fun `does not expire or emit side effects when a checkin changed during the Delius lookup`() {
    val checkin = checkin()
    whenever(checkinRepository.findEligibleForExpiry(any())).thenAnswer { Stream.of(checkin) }
    val details = ContactDetails(
      crn = checkin.offender.crn,
      name = Name("First", "Last"),
      dateOfBirth = LocalDate.of(1980, 1, 1),
      events = listOf(event(1L, LocalDate.of(2028, 1, 1))),
    )
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(checkin.offender.crn))).thenReturn(listOf(details))
    whenever(checkinRepository.updateStatusForCheckinId(checkin.id, CheckinStatus.CREATED, CheckinStatus.EXPIRED)).thenReturn(0)

    job.process()

    verify(checkinRepository).updateStatusForCheckinId(checkin.id, CheckinStatus.CREATED, CheckinStatus.EXPIRED)
    verify(checkinDeliusEventService, never()).recordCheckinUpdate(any(), any(), any(), any())
    verify(notificationService, never()).sendCheckinExpiredNotifications(any(), any())
    verify(eventAuditService, never()).recordCheckinExpired(any())
  }

  private fun checkin(): OffenderCheckin {
    val offender = Offender(
      uuid = UUID.randomUUID(),
      crn = "X123456",
      practitionerId = "PRACT001",
      status = OffenderStatus.VERIFIED,
      firstCheckin = LocalDate.of(2026, 10, 1),
      checkinInterval = CheckinInterval.WEEKLY.duration,
      createdAt = clock.instant(),
      createdBy = "PRACT001",
      updatedAt = clock.instant(),
      contactPreference = ContactPreference.EMAIL,
    )
    return OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = offender,
      status = CheckinStatus.CREATED,
      dueDate = LocalDate.of(2026, 10, 20),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
    )
  }

  private fun event(number: Long, expectedEndDate: LocalDate) = Event(
    number = number,
    mainOffence = CodedDescription("OFFENCE", "Offence"),
    sentence = Event.Sentence(
      date = LocalDate.of(2026, 1, 1),
      description = "Sentence",
      expectedEndDate = expectedEndDate,
      length = null,
      lengthUnit = null,
    ),
  )
}
