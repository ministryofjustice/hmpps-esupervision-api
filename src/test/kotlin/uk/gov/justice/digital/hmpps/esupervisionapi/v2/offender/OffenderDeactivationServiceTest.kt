package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatcher
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderDeactivatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SetupInfo
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
  private val appConfig: AppConfig = mock()
  private val offenderPersistenceService: OffenderPersistenceService = mock()
  private val offenderSetupRepository: OffenderSetupRepository = mock()

  private val service = OffenderDeactivationService(
    clock,
    appConfig,
    offenderPersistenceService,
    offenderSetupRepository,
  )

  init {
    whenever(appConfig.esup2305DeliusEventSelection).thenReturn(true)
  }

  private val contactDetails = ContactDetails(crn = "X123456", name = Name("John", "Doe"), mobile = "07700900123", dateOfBirth = LocalDate.of(1980, 1, 1))

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
    stubSuccessfulPersistence()

    val result = service.deactivateOffender(offender, "no active events", contactDetails, sensitive = true)

    assertEquals(true, result)
    assertEquals(OffenderStatus.VERIFIED, offender.status)
    val matcher = OffenderDeactivatedEventMatcher(offender, OffenderAuditEventType.OFFENDER_DEACTIVATED, "no active events")
    verify(offenderPersistenceService).offenderDeactivation(argThat(matcher), eq(false))
  }

  @Test
  fun `records the supplied audit event type (automated deactivation)`() {
    val offender = offender(OffenderStatus.VERIFIED)
    stubSuccessfulPersistence()

    service.deactivateOffender(
      offender,
      "no active events",
      contactDetails,
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS,
    )

    val matcher = OffenderDeactivatedEventMatcher(offender, OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS, "no active events")
    verify(offenderPersistenceService).offenderDeactivation(argThat(matcher), eq(true))
  }

  @Test
  fun `propagates the contact-suspended outcome code (automated deactivation)`() {
    val offender = offender(OffenderStatus.VERIFIED)
    stubSuccessfulPersistence()

    service.deactivateOffender(
      offender,
      "contact suspended",
      contactDetails,
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED,
    )

    val matcher = OffenderDeactivatedEventMatcher(offender, OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED, "contact suspended")
    verify(offenderPersistenceService).offenderDeactivation(argThat(matcher), eq(false))
  }

  @Test
  fun `stale selected event deactivation without setup retains the saved event number`() {
    val offender = offender(OffenderStatus.VERIFIED).apply { currentEvent = 1L }
    val details = contactDetails.copy(
      events = listOf(Event(2L, CodedDescription("X", "Another offence"), sentence = null)),
    )
    stubSuccessfulPersistence()

    service.deactivateOffender(
      offender = offender,
      reason = "Automatically deactivated: the selected probation event is no longer active in NDelius",
      contactDetails = details,
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_SELECTED_EVENT_NO_LONGER_ACTIVE,
    )

    val eventCaptor = argumentCaptor<OffenderDeactivatedEvent>()
    verify(offenderPersistenceService).offenderDeactivation(eventCaptor.capture(), eq(true))
    assertEquals(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_SELECTED_EVENT_NO_LONGER_ACTIVE, eventCaptor.firstValue.auditEventType)
    assertEquals("Automatically deactivated: the selected probation event is no longer active in NDelius", eventCaptor.firstValue.reason)
    assertEquals(null, eventCaptor.firstValue.setup)
    assertEquals(1L, eventCaptor.firstValue.activeEventNumber)
  }

  @Test
  fun `deactivation event with a setup does not fall back to an inactive selected event`() {
    val offender = offender(OffenderStatus.VERIFIED).apply { currentEvent = 1L }
    val setup = OffenderSetup(UUID.randomUUID(), offender, offender.practitionerId, clock.instant())
    whenever(offenderSetupRepository.findByOffender(offender)).thenReturn(Optional.of(setup))
    stubSuccessfulPersistence()

    service.deactivateOffender(
      offender,
      "Selected event no longer active",
      contactDetails,
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_SELECTED_EVENT_NO_LONGER_ACTIVE,
    )

    val eventCaptor = argumentCaptor<OffenderDeactivatedEvent>()
    verify(offenderPersistenceService).offenderDeactivation(eventCaptor.capture(), eq(true))
    assertEquals(SetupInfo.from(setup), eventCaptor.firstValue.setup)
    assertEquals(null, eventCaptor.firstValue.activeEventNumber)
    assertEquals(clock.instant(), eventCaptor.firstValue.offender.updatedAt)
  }

  @Test
  fun `event uses the evaluated selection snapshot and leaves the caller untouched when the guard fails`() {
    val offender = offender(OffenderStatus.VERIFIED).apply { currentEvent = 202L }

    val result = service.deactivateOffender(
      offender,
      "Selected event no longer active",
      contactDetails,
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_SELECTED_EVENT_NO_LONGER_ACTIVE,
      expectedCurrentEvent = 101L,
    )

    val eventCaptor = argumentCaptor<OffenderDeactivatedEvent>()
    verify(offenderPersistenceService).offenderDeactivation(eventCaptor.capture(), eq(true))
    assertEquals(false, result)
    assertEquals(101L, eventCaptor.firstValue.expectedCurrentEvent)
    assertEquals(101L, eventCaptor.firstValue.offender.currentEvent)
    assertEquals(101L, eventCaptor.firstValue.activeEventNumber)
    assertEquals(202L, offender.currentEvent)
    assertEquals(OffenderStatus.VERIFIED, offender.status)
  }

  @Test
  fun `is a no-op when offender is not VERIFIED`() {
    val offender = offender(OffenderStatus.INACTIVE)
    val result = service.deactivateOffender(offender, "in reset", contactDetails)
    assertEquals(false, result)
  }

  @Test
  fun `passes null contact details through when not supplied by caller`() {
    val offender = offender(OffenderStatus.VERIFIED)
    stubSuccessfulPersistence()

    service.deactivateOffender(offender, "no active events")

    val matcher = OffenderDeactivatedEventMatcher(offender, OffenderAuditEventType.OFFENDER_DEACTIVATED, "no active events")
    verify(offenderPersistenceService).offenderDeactivation(argThat(matcher), eq(false))
    verify(offenderPersistenceService).offenderDeactivation(argThat { this.offender.personalDetails == null }, eq(false))
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

  private fun stubSuccessfulPersistence() {
    whenever(offenderPersistenceService.offenderDeactivation(any(), any())).thenReturn(true)
  }
}
