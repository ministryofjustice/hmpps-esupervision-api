package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatcher
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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
      matches = matches && arg.reason == reason
      matches = matches && arg.auditEventType == auditEventType
      return matches
    }
  }

  @Test
  fun `deactivates a VERIFIED offender - status, audit and notification`() {
    val offender = offender(OffenderStatus.VERIFIED)
    stubSuccessfulPersistence()

    val result = service.deactivateOffender(offender, "no active events", practitionerMessageFragment = null, contactDetails = contactDetails, sensitive = true)

    assertEquals(true, result)
    assertEquals(OffenderStatus.VERIFIED, offender.status)
    val matcher = OffenderDeactivatedEventMatcher(offender, OffenderAuditEventType.OFFENDER_DEACTIVATED, "no active events")
    assertEquals(true, matcher.matches(capturedEvent()))
  }

  @Test
  fun `records the supplied audit event type (automated deactivation)`() {
    val offender = offender(OffenderStatus.VERIFIED)
    stubSuccessfulPersistence()

    service.deactivateOffender(
      offender,
      "no active events",
      practitionerMessageFragment = null,
      contactDetails = contactDetails,
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS,
    )

    val matcher = OffenderDeactivatedEventMatcher(offender, OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS, "no active events")
    assertEquals(true, matcher.matches(capturedEvent()))
  }

  @Test
  fun `propagates the contact-suspended outcome code (automated deactivation)`() {
    val offender = offender(OffenderStatus.VERIFIED)
    stubSuccessfulPersistence()

    service.deactivateOffender(
      offender,
      "contact suspended",
      practitionerMessageFragment = null,
      contactDetails = contactDetails,
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED,
    )

    val matcher = OffenderDeactivatedEventMatcher(offender, OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED, "contact suspended")
    assertEquals(true, matcher.matches(capturedEvent()))
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
      practitionerMessageFragment = null,
      contactDetails = details,
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_SELECTED_EVENT_NO_LONGER_ACTIVE,
    )

    val event = capturedEvent()
    assertEquals(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_SELECTED_EVENT_NO_LONGER_ACTIVE, event.auditEventType)
    assertEquals("Automatically deactivated: the selected probation event is no longer active in NDelius", event.reason)
    assertEquals(null, event.setup)
    assertEquals(1L, event.activeEventNumber)
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
      practitionerMessageFragment = null,
      contactDetails = contactDetails,
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_SELECTED_EVENT_NO_LONGER_ACTIVE,
    )

    val event = capturedEvent()
    assertEquals(SetupInfo.from(setup), event.setup)
    assertEquals(null, event.activeEventNumber)
    assertEquals(clock.instant(), event.offender.updatedAt)
  }

  @Test
  fun `evaluated selection is passed to the guard and the caller is untouched on failure`() {
    val offender = offender(OffenderStatus.VERIFIED).apply { currentEvent = 202L }

    val result = service.deactivateOffender(
      offender,
      "Selected event no longer active",
      practitionerMessageFragment = null,
      contactDetails = contactDetails,
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_SELECTED_EVENT_NO_LONGER_ACTIVE,
      expectedCurrentEvent = 101L,
    )

    assertEquals(101L, capturedEvent().expectedCurrentEvent)
    assertEquals(101L, capturedEvent().offender.currentEvent)
    assertEquals(false, result)
    assertEquals(202L, offender.currentEvent)
    assertEquals(OffenderStatus.VERIFIED, offender.status)
  }

  @Test
  fun `event deactivation keeps the caller's setup and resolves the event number from the evaluated selection`() {
    val offender = offender(OffenderStatus.VERIFIED).apply { currentEvent = 101L }
    val setup = OffenderSetup(UUID.randomUUID(), offender, offender.practitionerId, clock.instant())
    val details = contactDetails.copy(events = listOf(Event(101L, CodedDescription("X", "Current offence"), sentence = null)))
    stubSuccessfulPersistence()
    val event = OffenderDeactivatedEvent(
      offenderId = offender.id,
      offender = offender.dto(details),
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_INELIGIBLE,
      setup = SetupInfo.from(setup),
      activeEventNumber = null,
    )

    assertEquals(true, service.deactivateOffender(offender, event))

    val persisted = capturedEvent()
    assertEquals(offender.dto(details).copy(status = OffenderStatus.INACTIVE, updatedAt = clock.instant()), persisted.offender)
    assertEquals(SetupInfo.from(setup), persisted.setup)
    assertEquals(101L, persisted.expectedCurrentEvent)
    assertEquals(101L, persisted.activeEventNumber)
    verify(offenderSetupRepository, never()).findByOffender(any())
  }

  @Test
  fun `is a no-op when offender is not VERIFIED`() {
    val offender = offender(OffenderStatus.INACTIVE)
    val result = service.deactivateOffender(offender, "in reset", practitionerMessageFragment = null, contactDetails = contactDetails)
    assertEquals(false, result)
  }

  @Test
  fun `passes null contact details through when not supplied by caller`() {
    val offender = offender(OffenderStatus.VERIFIED)
    stubSuccessfulPersistence()

    service.deactivateOffender(offender, "no active events", null)

    val matcher = OffenderDeactivatedEventMatcher(offender, OffenderAuditEventType.OFFENDER_DEACTIVATED, "no active events")
    val event = capturedEvent()
    assertEquals(true, matcher.matches(event))
    assertEquals(null, event.offender.personalDetails)
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
    whenever(offenderPersistenceService.offenderDeactivation(any())).thenReturn(true)
  }

  private fun capturedEvent(): OffenderDeactivatedEvent {
    val captor = argumentCaptor<OffenderDeactivatedEvent>()
    verify(offenderPersistenceService).offenderDeactivation(captor.capture())
    return captor.firstValue
  }
}
