package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatcher
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderDeactivatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

class OffenderDeactivationServiceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-10T10:00:00Z"), ZoneId.of("UTC"))
  private val offenderRepository: OffenderRepository = mock()
  private val offenderSetupRepository: OffenderSetupRepository = mock()
  private val offenderPersistenceService: OffenderPersistenceService = mock()

  private val service = OffenderDeactivationService(
    clock,
    offenderSetupRepository,
    offenderPersistenceService,
  )

  private val contactDetails = ContactDetails(crn = "X123456", name = Name("John", "Doe"), mobile = "07700900123")

  class OffenderDeactivatedEventMatcher(
    private val offender: Offender,
    private val auditEventType: OffenderAuditEventType,
    private val reason: String?,
  ) : ArgumentMatcher<OffenderDeactivatedEvent> {
    override fun matches(arg: OffenderDeactivatedEvent): Boolean {
      var matches = arg.offenderId == offender.id
      matches = matches && arg.offender.crn == offender.crn
      matches = matches && arg.offender.status == OffenderStatus.INACTIVE
      matches = matches && arg.reason == reason
      matches = matches && arg.auditEventType == auditEventType
      return matches
    }
  }

  @Test
  fun `deactivates a VERIFIED offender - status, audit and notification`() {
    val offender = offender(OffenderStatus.VERIFIED)
    whenever(offenderRepository.save(any<Offender>())).thenAnswer { it.getArgument<Offender>(0) }
    whenever(offenderSetupRepository.findByOffender(any())).thenReturn(Optional.empty())

    val result = service.deactivateOffender(offender, "no active events", contactDetails, sensitive = true)

    assertEquals(OffenderStatus.INACTIVE, result.status)
    val matcher = OffenderDeactivatedEventMatcher(offender, OffenderAuditEventType.OFFENDER_DEACTIVATED, "no active events")
    verify(offenderPersistenceService).offenderDeactivation(any(), argThat(matcher))
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

    val matcher = OffenderDeactivatedEventMatcher(offender, OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS, "no active events")
    verify(offenderPersistenceService).offenderDeactivation(any(), argThat(matcher))
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

    val matcher = OffenderDeactivatedEventMatcher(offender, OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED, "contact suspended")
    verify(offenderPersistenceService).offenderDeactivation(any(), argThat(matcher))
  }

  @Test
  fun `is a no-op when offender is not VERIFIED`() {
    val offender = offender(OffenderStatus.INACTIVE)
    val result = service.deactivateOffender(offender, "in reset", contactDetails)
    assertEquals(OffenderStatus.INACTIVE, result.status)
  }

  @Test
  fun `passes null contact details through when not supplied by caller`() {
    val offender = offender(OffenderStatus.VERIFIED)
    whenever(offenderRepository.save(any<Offender>())).thenAnswer { it.getArgument<Offender>(0) }
    whenever(offenderSetupRepository.findByOffender(any())).thenReturn(Optional.empty())

    service.deactivateOffender(offender, "no active events")

    val matcher = OffenderDeactivatedEventMatcher(offender, OffenderAuditEventType.OFFENDER_DEACTIVATED, "no active events")
    verify(offenderPersistenceService).offenderDeactivation(any(), argThat(matcher))
    verify(offenderPersistenceService).offenderDeactivation(any(), argThat { this.offender.personalDetails == null })
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
