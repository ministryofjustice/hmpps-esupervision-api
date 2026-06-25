package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.asSetupDto
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.AdditionalInformation
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class NotificationOrchestratorServiceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-03T10:00:00Z"), ZoneId.of("UTC"))
  private val notificationPersistence: NotificationPersistenceService = mock()
  private val notifyGateway: NotifyGatewayService = mock()
  private val domainEventService: DomainEventService = mock()
  private val eventAuditService: EventAuditService = mock()
  private val eventDetailService: EventDetailService = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val appConfig: AppConfig = mock()

  private lateinit var service: NotificationOrchestratorService

  @BeforeEach
  fun setUp() {
    whenever(appConfig.checkinSubmitUrlV2(any())).thenReturn(URI("https://example.com/submitv2"))
    whenever(appConfig.checkinReviewUrlV2(any(), any())).thenReturn(URI("https://example.com/reviewv2"))
    whenever(appConfig.feedbackUrl()).thenReturn(URI("https://example.com/feedback"))

    service = NotificationOrchestratorService(
      notificationPersistence,
      notifyGateway,
      domainEventService,
      eventAuditService,
      eventDetailService,
      ndiliusApiClient,
      appConfig,
      clock,
      Duration.ofHours(72),
    )
  }

  @Test
  fun `sendSetupCompletedNotifications - happy path - sends notifications and publishes event`() {
    val offender = createOffender()
    val contactDetails = createContactDetails()

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(contactDetails)

    service.sendSetupCompletedNotifications(offender, contactDetails, offender.asSetupDto(clock))

    verify(domainEventService).publishDomainEvent(any(), eq(offender.uuid), eq(offender.crn), any(), eq(null), any())
  }

  @Test
  fun `sendSetupCompletedNotifications - missing contact details - still publishes event and records audit`() {
    val offender = createOffender()

    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(null)

    val setupDto = offender.asSetupDto(clock)
    service.sendSetupCompletedNotifications(offender, null, setupDto)

    // Domain event ALWAYS published (even without contact details)
    verify(domainEventService).publishDomainEvent(any(), eq(offender.uuid), eq(offender.crn), any(), eq(null), any())
    // Audit event ALWAYS recorded (even with null contact details)
    verify(eventAuditService).recordSetupCompleted(offender, null, setupDto)
    // Notifications NOT sent (because contact details missing)
    verify(notificationPersistence, never()).saveNotifications(any())
  }

  @Test
  fun `sendCheckinCreatedNotifications - happy path - sends to offender only`() {
    val offender = createOffender()
    val checkin = createCheckin(offender)
    val contactDetails = createContactDetails()

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())

    val event = CheckinCreatedEvent(
      checkin = checkin.dto(contactDetails, clock = clock),
      offenderId = offender.id,
      checkinId = checkin.id,
      practitionerId = offender.practitionerId,
      offenderContactPreference = offender.contactPreference,
      currentEvent = null,
    )
    service.sendCheckinCreatedNotifications(event)

    // we only notify the offender about the checkin invite (no practitioner template)
    verify(notificationPersistence).buildOffenderNotifications(any(), any(), any(), any(), eq(NotificationType.OffenderCheckinInvite))
    verify(notificationPersistence, never()).buildPractitionerNotifications(any(), any(), any(), any(), any(), any())
    verify(domainEventService).publishDomainEvent(any(), eq(checkin.uuid), eq(checkin.offender.crn), any(), eq(null), eq(null))
    verify(ndiliusApiClient, never()).getContactDetails(any())
  }

  @Test
  fun `sendReminderCheckinNotifications - happy path - sends to offender only`() {
    val offender = createOffender()
    val checkin = createCheckin(offender)
    val contactDetails = createContactDetails()

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())

    service.sendReminderCheckinNotifications(checkin, contactDetails)

    verify(notificationPersistence).buildOffenderNotifications(any(), any(), any(), any(), eq(NotificationType.OffenderCheckinReminder))
    verify(notificationPersistence, never()).buildPractitionerNotifications(any(), any(), any(), any(), any(), any())
    verify(ndiliusApiClient, never()).getContactDetails(any())
  }

  @Test
  fun `sendCheckinSubmittedNotifications test`() {
    val offender = createOffender()
    val checkin = createCheckin(offender, status = CheckinStatus.SUBMITTED)
    val contactDetails = createContactDetails()

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.buildPractitionerNotifications(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(contactDetails)

    val event = CheckinSubmittedEvent(checkin.id, offender.id, offender.practitionerId, checkin.dto(contactDetails, clock = clock), offender.contactPreference)
    service.sendCheckinSubmittedNotifications(event)

    verify(domainEventService).publishDomainEvent(any(), eq(checkin.uuid), eq(checkin.offender.crn), any(), eq(null), eq(null))
    verify(eventAuditService, never()).recordCheckinSubmitted(checkin, event)
  }

  @Test
  fun `verify practitioner details for checkin submitted notifications`() {
    val offender = createOffender()
    val checkin = createCheckin(offender, status = CheckinStatus.SUBMITTED)
    val contactDetails = createContactDetails()

    val personalisation = service.checkinSubmittedPersonalisationDetails(contactDetails, checkin.dto(contactDetails, clock = clock), 4, "no")
    assertEquals("Jane", personalisation["practitionerName"])
    assertEquals("John Smith", personalisation["name"])
  }

  @Test
  fun `sendCheckinExpiredNotifications - only sends to practitioner`() {
    val offender = createOffender()
    val checkin = createCheckin(offender, status = CheckinStatus.EXPIRED)
    val contactDetails = createContactDetails()

    whenever(notificationPersistence.buildPractitionerNotifications(any(), any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(contactDetails)

    service.sendCheckinExpiredNotifications(checkin, contactDetails)

    verify(notificationPersistence, never()).buildOffenderNotifications(any(), any(), any(), any(), any())
    verify(notificationPersistence).buildPractitionerNotifications(any(), any(), any(), eq(checkin.dto(contactDetails)), eq(NotificationType.PractitionerCheckinMissed), any())
    verify(domainEventService).publishDomainEvent(any(), eq(checkin.uuid), eq(checkin.offender.crn), any(), eq(null), eq(null))
  }

  @Test
  fun `sendCheckinReviewedNotifications - publishes event and records audit`() {
    val offender = createOffender()
    val checkin = createCheckin(offender, status = CheckinStatus.REVIEWED)
    val contactDetails = createContactDetails()

    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(contactDetails)

    val event = CheckinReviewedEvent(checkin.id, offender.id, offender.practitionerId, checkin.dto(contactDetails, clock = clock), offender.contactPreference)
    service.sendCheckinReviewedNotifications(event)

    verify(domainEventService).publishDomainEvent(any(), eq(checkin.uuid), eq(checkin.offender.crn), any(), eq(null), eq(null))
    verify(eventAuditService, never()).recordCheckinReviewed(checkin, event)
  }

  @Test
  fun `notification failure - still publishes domain event`() {
    val offender = createOffender()
    val contactDetails = createContactDetails()
    val notifications = listOf(
      GenericNotification(
        notificationId = UUID.randomUUID(),
        eventType = "SETUP_COMPLETED",
        recipientType = "OFFENDER",
        channel = "SMS",
        reference = "REF-001",
        createdAt = clock.instant(),
      ),
    )

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any(), any(), any()))
      .thenReturn(notifications.map { NotificationWithRecipient(it, "07700900123", AssociatedOffenderInfo.create(offender.crn)) })
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(notifications)
    whenever(notifyGateway.send(any(), any(), any(), any(), any()))
      .thenThrow(RuntimeException("GOV.UK Notify error"))
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(contactDetails)

    service.sendSetupCompletedNotifications(offender, contactDetails, offender.asSetupDto(clock))

    verify(domainEventService).publishDomainEvent(any(), eq(offender.uuid), eq(offender.crn), any(), eq(null), any())
  }

  @Test
  fun `sendSetupCompletedNotifications - includes event number when contact details have events`() {
    val offender = createOffender()
    val contactDetails = createContactDetailsWithEvents()

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())

    val setupDto = offender.asSetupDto(clock)
    service.sendSetupCompletedNotifications(offender, contactDetails, setupDto)

    verify(domainEventService).publishDomainEvent(
      eq(DomainEventType.V2_SETUP_COMPLETED),
      eq(offender.uuid),
      eq(offender.crn),
      any(),
      eq(null),
      eq(AdditionalInformation(eventNumber = 12345L, setupId = setupDto.setupId)),
    )
  }

  @Test
  fun `sendSetupCompletedNotifications - publishes event without eventNumber when no events`() {
    val offender = createOffender()
    val contactDetails = createContactDetails().copy(events = emptyList())

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())

    val setupDto = offender.asSetupDto(clock)
    service.sendSetupCompletedNotifications(offender, contactDetails, setupDto)

    verify(domainEventService).publishDomainEvent(
      eq(DomainEventType.V2_SETUP_COMPLETED),
      eq(offender.uuid),
      eq(offender.crn),
      any(),
      eq(null),
      eq(AdditionalInformation(eventNumber = null, setupId = setupDto.setupId)),
    )
  }

  @Test
  fun `sendReactivationCompletedNotifications - publishes setup completed domain event with event number`() {
    val offender = createOffender()
    val contactDetails = createContactDetailsWithEvents()

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())

    service.sendReactivationCompletedNotifications(offender, contactDetails)

    verify(domainEventService).publishDomainEvent(
      eq(DomainEventType.V2_SETUP_COMPLETED),
      eq(offender.uuid),
      eq(offender.crn),
      any(),
      eq(null),
      eq(AdditionalInformation(eventNumber = 12345L, setupId = null)),
    )
  }

  @Test
  fun `sendReactivationCompletedNotifications - publishes event without eventNumber when no events`() {
    val offender = createOffender()
    val contactDetails = createContactDetails().copy(events = emptyList())

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())

    service.sendReactivationCompletedNotifications(offender, contactDetails)

    verify(domainEventService).publishDomainEvent(
      eq(DomainEventType.V2_SETUP_COMPLETED),
      eq(offender.uuid),
      eq(offender.crn),
      any(),
      eq(null),
      eq(AdditionalInformation(eventNumber = null, setupId = null)),
    )
  }

  @Test
  fun `sendDeactivationCompletedNotifications - publishes setup removed domain event`() {
    val offender = createOffender()
    val contactDetails = createContactDetailsWithEvents()

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())

    service.sendDeactivationCompletedNotifications(offender, contactDetails)

    verify(domainEventService).publishDomainEvent(
      eq(DomainEventType.V2_SETUP_REMOVED),
      eq(offender.uuid),
      eq(offender.crn),
      any(),
      eq(null),
      eq(AdditionalInformation(eventNumber = 12345L, setupId = null)),
    )
  }

  @Test
  fun `sendDeactivationCompletedNotifications - includes reason code in domain event`() {
    val offender = createOffender()
    val contactDetails = createContactDetailsWithEvents()

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())

    service.sendDeactivationCompletedNotifications(offender, contactDetails, reason = "ESPRS")

    verify(domainEventService).publishDomainEvent(
      eq(DomainEventType.V2_SETUP_REMOVED),
      eq(offender.uuid),
      eq(offender.crn),
      any(),
      eq(null),
      eq(AdditionalInformation(eventNumber = 12345L, setupId = null, reason = "ESPRS")),
    )
  }

  @Test
  fun `sendDeactivationCompletedNotifications - publishes event without eventNumber when no events`() {
    val offender = createOffender()
    val contactDetails = createContactDetails().copy(events = emptyList())

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())

    service.sendDeactivationCompletedNotifications(offender, contactDetails)

    verify(domainEventService).publishDomainEvent(
      eq(DomainEventType.V2_SETUP_REMOVED),
      eq(offender.uuid),
      eq(offender.crn),
      any(),
      eq(null),
      eq(AdditionalInformation(eventNumber = null, setupId = null)),
    )
  }

  private fun createOffender(status: OffenderStatus = OffenderStatus.VERIFIED) = Offender(
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

  private fun createCheckin(
    offender: Offender,
    status: CheckinStatus = CheckinStatus.CREATED,
  ) = OffenderCheckin(
    uuid = UUID.randomUUID(),
    offender = offender,
    status = status,
    dueDate = LocalDate.now(clock),
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
  )

  private fun createContactDetailsWithEvents() = ContactDetails(
    crn = "X123456",
    name = Name("John", "Smith"),
    mobile = "07700900123",
    email = "test@test.com",
    practitioner = PractitionerDetails(
      name = Name("Jane", "Doe"),
      email = "jane.doe@probation.gov.uk",
      localAdminUnit = OrganizationalUnit("LAU01", "Test LAU"),
      probationDeliveryUnit = OrganizationalUnit("PDU01", "Test PDU"),
      provider = OrganizationalUnit("PRV01", "Test Provider"),
    ),
    events = listOf(Event(number = 12345L, mainOffence = CodedDescription("OFF01", "Test Offence"), sentence = null)),
  )

  private fun createContactDetails() = ContactDetails(
    crn = "X123456",
    name = Name("John", "Smith"),
    mobile = "07700900123",
    email = "test@test.com",
    practitioner = PractitionerDetails(
      name = Name("Jane", "Doe"),
      email = "jane.doe@probation.gov.uk",
      localAdminUnit = OrganizationalUnit("LAU01", "Test LAU"),
      probationDeliveryUnit = OrganizationalUnit("PDU01", "Test PDU"),
      provider = OrganizationalUnit("PRV01", "Test Provider"),
    ),
    events = listOf(Event(1, mainOffence = CodedDescription("OFF01", "Test Offence"), sentence = null)),
  )
}
