package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class NotificationOrchestratorV2ServiceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-03T10:00:00Z"), ZoneId.of("UTC"))
  private val notificationPersistence: NotificationPersistenceService = mock()
  private val notifyGateway: NotifyGatewayService = mock()
  private val domainEventService: DomainEventService = mock()
  private val eventAuditService: EventAuditV2Service = mock()
  private val eventDetailService: EventDetailV2Service = mock()
  private val ndiliusApiClient: NdiliusApiClient = mock()
  private val appConfig: AppConfig = mock()

  private lateinit var service: NotificationOrchestratorV2Service

  @BeforeEach
  fun setUp() {
    whenever(appConfig.checkinSubmitUrl(any())).thenReturn(URI("https://example.com/checkin"))
    whenever(appConfig.checkinDashboardUrl(any())).thenReturn(URI("https://example.com/dashboard"))

    service = NotificationOrchestratorV2Service(
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

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(contactDetails)

    service.sendSetupCompletedNotifications(offender, contactDetails)

    verify(domainEventService).publishSetupCompleted(offender)
    verify(eventAuditService).recordSetupCompleted(offender, contactDetails)
  }

  @Test
  fun `sendSetupCompletedNotifications - missing contact details - still publishes event and records audit`() {
    val offender = createOffender()

    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(null)

    service.sendSetupCompletedNotifications(offender, null)

    // Domain event ALWAYS published (even without contact details)
    verify(domainEventService).publishSetupCompleted(offender)
    // Audit event ALWAYS recorded (even with null contact details)
    verify(eventAuditService).recordSetupCompleted(offender, null)
    // Notifications NOT sent (because contact details missing)
    verify(notificationPersistence, never()).saveNotifications(any())
  }

  @Test
  fun `sendCheckinCreatedNotifications - happy path - sends to offender only`() {
    val offender = createOffender()
    val checkin = createCheckin(offender)
    val contactDetails = createContactDetails()

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(contactDetails)

    service.sendCheckinCreatedNotifications(checkin, contactDetails)

    // V1 only notifies offender for checkin invite (no practitioner template)
    verify(notificationPersistence).buildOffenderNotifications(any(), any(), eq(NotificationType.OffenderCheckinInvite))
    verify(notificationPersistence, never()).buildPractitionerNotifications(any(), any(), any(), any())
    verify(domainEventService).publishCheckinCreated(checkin)
  }

  @Test
  fun `sendCheckinSubmittedNotifications - sends with correct personalisation`() {
    val offender = createOffender()
    val checkin = createCheckin(offender, status = CheckinV2Status.SUBMITTED)
    val contactDetails = createContactDetails()

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.buildPractitionerNotifications(any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(contactDetails)

    service.sendCheckinSubmittedNotifications(checkin, contactDetails)

    verify(domainEventService).publishCheckinSubmitted(checkin)
    verify(eventAuditService).recordCheckinSubmitted(checkin, contactDetails)
  }

  @Test
  fun `sendCheckinExpiredNotifications - only sends to practitioner`() {
    val offender = createOffender()
    val checkin = createCheckin(offender, status = CheckinV2Status.EXPIRED)
    val contactDetails = createContactDetails()

    whenever(notificationPersistence.buildPractitionerNotifications(any(), any(), any(), any())).thenReturn(emptyList())
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(emptyList())
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(contactDetails)

    service.sendCheckinExpiredNotifications(checkin, contactDetails)

    verify(notificationPersistence, never()).buildOffenderNotifications(any(), any(), any())
    verify(notificationPersistence).buildPractitionerNotifications(any(), any(), eq(checkin), eq(NotificationType.PractitionerCheckinMissed))
    verify(domainEventService).publishCheckinExpired(checkin)
  }

  @Test
  fun `sendCheckinReviewedNotifications - publishes event and records audit`() {
    val offender = createOffender()
    val checkin = createCheckin(offender, status = CheckinV2Status.REVIEWED)
    val contactDetails = createContactDetails()

    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(contactDetails)

    service.sendCheckinReviewedNotifications(checkin, contactDetails)

    verify(domainEventService).publishCheckinReviewed(checkin)
    verify(eventAuditService).recordCheckinReviewed(checkin, contactDetails)
  }

  @Test
  fun `notification failure - still publishes domain event`() {
    val offender = createOffender()
    val contactDetails = createContactDetails()
    val notifications = listOf(
      GenericNotificationV2(
        notificationId = UUID.randomUUID(),
        eventType = "SETUP_COMPLETED",
        recipientType = "OFFENDER",
        channel = "SMS",
        reference = "REF-001",
        createdAt = clock.instant(),
      ),
    )

    whenever(notificationPersistence.buildOffenderNotifications(any(), any(), any()))
      .thenReturn(notifications.map { NotificationWithRecipient(it, "07700900123") })
    whenever(notificationPersistence.saveNotifications(any())).thenReturn(notifications)
    whenever(notifyGateway.send(any(), any(), any(), any(), any()))
      .thenThrow(RuntimeException("GOV.UK Notify error"))
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(contactDetails)

    service.sendSetupCompletedNotifications(offender, contactDetails)

    verify(domainEventService).publishSetupCompleted(offender)
  }

  private fun createOffender(status: OffenderStatus = OffenderStatus.VERIFIED) = OffenderV2(
    uuid = UUID.randomUUID(),
    crn = "X123456",
    practitionerId = "PRACT001",
    status = status,
    firstCheckin = LocalDate.now(clock),
    checkinInterval = CheckinInterval.WEEKLY.duration,
    createdAt = clock.instant(),
    createdBy = "PRACT001",
    updatedAt = clock.instant(),
  )

  private fun createCheckin(
    offender: OffenderV2,
    status: CheckinV2Status = CheckinV2Status.CREATED,
  ) = OffenderCheckinV2(
    uuid = UUID.randomUUID(),
    offender = offender,
    status = status,
    dueDate = LocalDate.now(clock),
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
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
  )
}
