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
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
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

class OffenderDeactivationServiceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-10T10:00:00Z"), ZoneId.of("UTC"))
  private val offenderRepository: OffenderRepository = mock()
  private val checkinRepository: OffenderCheckinRepository = mock()
  private val offenderSetupRepository: OffenderSetupRepository = mock()
  private val questionService: QuestionService = mock()
  private val eventAuditService: EventAuditService = mock()
  private val notificationService: NotificationService = mock()

  private val service = OffenderDeactivationService(
    clock,
    offenderRepository,
    checkinRepository,
    offenderSetupRepository,
    questionService,
    eventAuditService,
    notificationService,
  )

  private val contactDetails = ContactDetails(crn = "X123456", name = Name("John", "Doe"), mobile = "07700900123")

  @Test
  fun `deactivates a VERIFIED offender - status, audit and notification`() {
    val offender = offender(OffenderStatus.VERIFIED)
    whenever(offenderRepository.save(any<Offender>())).thenAnswer { it.getArgument<Offender>(0) }
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
    verify(notificationService).sendDeactivationCompletedNotifications(eq(offender), eq(contactDetails), isNull(), eq("ESPMP"))
  }

  @Test
  fun `records the supplied audit event type (automated deactivation)`() {
    val offender = offender(OffenderStatus.VERIFIED)
    whenever(offenderRepository.save(any<Offender>())).thenAnswer { it.getArgument<Offender>(0) }
    whenever(offenderSetupRepository.findByOffender(any())).thenReturn(Optional.empty())

    service.deactivateOffender(
      offender,
      "no active events",
      contactDetails,
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS,
    )

    verify(eventAuditService).recordOffenderEvent(
      eq(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS),
      eq(offender),
      eq(contactDetails),
      eq("no active events"),
      eq(false),
    )
    verify(notificationService).sendDeactivationCompletedNotifications(eq(offender), eq(contactDetails), isNull(), eq("ESPNA"))
  }

  @Test
  fun `propagates the contact-suspended outcome code (automated deactivation)`() {
    val offender = offender(OffenderStatus.VERIFIED)
    whenever(offenderRepository.save(any<Offender>())).thenAnswer { it.getArgument<Offender>(0) }
    whenever(offenderSetupRepository.findByOffender(any())).thenReturn(Optional.empty())

    service.deactivateOffender(
      offender,
      "contact suspended",
      contactDetails,
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED,
    )

    verify(eventAuditService).recordOffenderEvent(
      eq(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED),
      eq(offender),
      eq(contactDetails),
      eq("contact suspended"),
      eq(false),
    )
    verify(notificationService).sendDeactivationCompletedNotifications(eq(offender), eq(contactDetails), isNull(), eq("ESPRS"))
  }

  @Test
  fun `cancels pending CREATED check-ins on deactivation`() {
    val offender = offender(OffenderStatus.VERIFIED)
    whenever(offenderRepository.save(any<Offender>())).thenAnswer { it.getArgument<Offender>(0) }
    whenever(offenderSetupRepository.findByOffender(any())).thenReturn(Optional.empty())

    service.deactivateOffender(offender, "in reset", contactDetails)

    verify(checkinRepository).updateStatusForOffender(offender, CheckinStatus.CREATED, CheckinStatus.CANCELLED)
  }

  @Test
  fun `is a no-op when offender is not VERIFIED`() {
    val offender = offender(OffenderStatus.INACTIVE)

    val result = service.deactivateOffender(offender, "in reset", contactDetails)

    assertEquals(OffenderStatus.INACTIVE, result.status)
    verify(offenderRepository, never()).save(any())
    verify(questionService, never()).deleteUpcomingAssignment(any())
    verify(notificationService, never()).sendDeactivationCompletedNotifications(any(), any(), any(), any())
  }

  @Test
  fun `passes null contact details through when not supplied by caller`() {
    val offender = offender(OffenderStatus.VERIFIED)
    whenever(offenderRepository.save(any<Offender>())).thenAnswer { it.getArgument<Offender>(0) }
    whenever(offenderSetupRepository.findByOffender(any())).thenReturn(Optional.empty())

    service.deactivateOffender(offender, "no active events")

    verify(notificationService).sendDeactivationCompletedNotifications(eq(offender), isNull(), isNull(), eq("ESPMP"))
  }

  private fun offender(status: OffenderStatus) = Offender(
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
