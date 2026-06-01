package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.question.QuestionService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

class OffenderDeactivationV2ServiceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-10T10:00:00Z"), ZoneId.of("UTC"))
  private val offenderRepository: OffenderV2Repository = mock()
  private val checkinRepository: OffenderCheckinV2Repository = mock()
  private val offenderSetupRepository: OffenderSetupV2Repository = mock()
  private val questionService: QuestionService = mock()
  private val eventAuditService: EventAuditV2Service = mock()
  private val notificationService: NotificationV2Service = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()

  private val service = OffenderDeactivationV2Service(
    clock,
    offenderRepository,
    checkinRepository,
    offenderSetupRepository,
    questionService,
    eventAuditService,
    notificationService,
    ndiliusApiClient,
  )

  private val contactDetails = ContactDetails(crn = "X123456", name = Name("John", "Doe"), mobile = "07700900123")

  @Test
  fun `deactivates a VERIFIED offender - status, audit and notification`() {
    val offender = offender(OffenderStatus.VERIFIED)
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(offenderSetupRepository.findByOffender(any())).thenReturn(Optional.empty())

    val result = service.deactivateOffender(offender, "no active events", contactDetails, sensitive = true)

    assertEquals(OffenderStatus.INACTIVE, result.status)
    verify(offenderRepository).save(offender)
    verify(questionService).deleteUpcomingAssignment(offender.crn)
    verify(eventAuditService).recordOffenderEvent(
      eq(OffenderAuditEventType.OFFENDER_DEACTIVATED),
      eq(offender),
      eq(contactDetails),
      eq("no active events"),
      eq(true),
    )
    verify(notificationService).sendDeactivationCompletedNotifications(eq(offender), eq(contactDetails), isNull())
  }

  @Test
  fun `cancels pending CREATED check-ins on deactivation`() {
    val offender = offender(OffenderStatus.VERIFIED)
    val pending = OffenderCheckinV2(
      uuid = UUID.randomUUID(),
      offender = offender,
      status = CheckinV2Status.CREATED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
    )
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(offenderSetupRepository.findByOffender(any())).thenReturn(Optional.empty())
    whenever(checkinRepository.findAllByOffenderAndStatus(offender, CheckinV2Status.CREATED))
      .thenReturn(listOf(pending))

    service.deactivateOffender(offender, "in reset", contactDetails)

    assertEquals(CheckinV2Status.CANCELLED, pending.status)
    verify(checkinRepository).saveAll(listOf(pending))
  }

  @Test
  fun `is a no-op when offender is not VERIFIED`() {
    val offender = offender(OffenderStatus.INACTIVE)

    val result = service.deactivateOffender(offender, "in reset", contactDetails)

    assertEquals(OffenderStatus.INACTIVE, result.status)
    verify(offenderRepository, never()).save(any())
    verify(questionService, never()).deleteUpcomingAssignment(any())
    verify(notificationService, never()).sendDeactivationCompletedNotifications(any(), any(), any())
  }

  @Test
  fun `fetches contact details from NDelius when not supplied`() {
    val offender = offender(OffenderStatus.VERIFIED)
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(offenderSetupRepository.findByOffender(any())).thenReturn(Optional.empty())
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)

    service.deactivateOffender(offender, "no active events")

    verify(ndiliusApiClient).getContactDetails(offender.crn)
    verify(notificationService).sendDeactivationCompletedNotifications(eq(offender), eq(contactDetails), isNull())
  }

  private fun offender(status: OffenderStatus) = OffenderV2(
    uuid = UUID.randomUUID(),
    crn = "X123456",
    practitionerId = "PRACT001",
    status = status,
    firstCheckin = LocalDate.now(clock),
    checkinInterval = CheckinInterval.WEEKLY.duration,
    createdAt = clock.instant(),
    createdBy = "PRACT001",
    updatedAt = clock.instant(),
    contactPreference = ContactPreference.PHONE,
  )
}
