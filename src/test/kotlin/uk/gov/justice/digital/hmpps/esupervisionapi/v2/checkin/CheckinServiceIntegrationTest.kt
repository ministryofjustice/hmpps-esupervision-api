package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.OffenderPhotoClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AnnotateCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinSubmittedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.LogEntryType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ReviewCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SubmitCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SurveyVersion
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.OffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.CheckinExpiryJob
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotificationOrchestratorService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotificationPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotifyGatewayService
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CheckinServiceIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var checkinService: CheckinService

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinV2Repository: OffenderCheckinRepository

  @Autowired
  private lateinit var offenderEventLogRepository: OffenderEventLogRepository

  @Autowired
  private lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired
  private lateinit var genericNotificationRepository: GenericNotificationRepository

  @Autowired
  private lateinit var eventAuditRepository: EventAuditRepository

  @Autowired
  private lateinit var notificationPersistenceService: NotificationPersistenceService

  @Autowired
  private lateinit var appConfig: AppConfig

  @Autowired
  private lateinit var clock: Clock

  @Autowired
  private lateinit var checkinExpiryJob: CheckinExpiryJob

  @MockitoBean
  private lateinit var ndiliusApiClient: INdiliusApiClient

  @MockitoBean
  private lateinit var notificationService: NotificationOrchestratorService

  @MockitoBean
  private lateinit var s3UploadService: S3UploadService

  @MockitoBean
  private lateinit var offenderPhotoClient: OffenderPhotoClient

  @MockitoBean
  private lateinit var offenderIdVerifier: OffenderIdVerifier

  @MockitoBean
  private lateinit var checkinCreationService: CheckinCreationService

  @AfterEach
  fun tearDown() {
    genericNotificationRepository.deleteAll()
    offenderEventLogRepository.deleteAll()
    checkinV2Repository.deleteAll()
    offenderRepository.deleteAll()
    outboxItemRepository.deleteAll()
    reset(s3UploadService)
  }

  @Test
  fun `getCheckin - happy path - returns checkin details`() {
    val offender = offenderTemplate.copy().toEntity()
    offenderRepository.save(offender)

    // we want some content in the checkin logs, so we add a CHECKIN_NOT_SUBMITTED event and
    // set the checkin status to match
    val checkin = checkinV2Repository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.EXPIRED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
      ),
    )

    offenderEventLogRepository.save(
      OffenderEventLog(
        "he forgot",
        sensitive = false,
        Instant.now(),
        logEntryType = LogEntryType.OFFENDER_CHECKIN_NOT_SUBMITTED,
        practitioner = offender.practitionerId,
        uuid = UUID.randomUUID(),
        offender = offender,
        checkin = checkin.id,
      ),
    )

    whenever(s3UploadService.getCheckinVideo(any())).thenReturn(URI.create("https://s3/video.mp4").toURL())
    whenever(s3UploadService.getCheckinSnapshot(any(), eq(0))).thenReturn(URI.create("https://s3/snapshot.jpg").toURL())
    val photoUrl = URI.create("https://s3/photo.jpg").toURL()
    whenever(offenderPhotoClient.presignRead(offender.uuid)).thenReturn(photoUrl)

    val result = checkinService.getCheckin(checkin.uuid, includePersonalDetails = false)

    assertEquals(checkin.uuid, result.uuid)
    assertEquals(offender.crn, result.crn)
    assertEquals(CheckinStatus.EXPIRED, result.status)
    assertNotNull(result.videoUrl)
    assertNotNull(result.snapshotUrl)
    assertEquals(photoUrl, result.photoUrl)
    assertNull(result.personalDetails)
    assertEquals(LogEntryType.OFFENDER_CHECKIN_NOT_SUBMITTED, result.checkinLogs.logs[0].logEntryType)
  }

  @Test
  fun `submissions retain historical attribution and reconcile reallocation without extra Delius calls`() {
    val offender = offenderRepository.save(
      offenderTemplate.copy(crn = "X987650", practitionerId = "OLD").toEntity().apply {
        updatedAt = Instant.parse("2020-01-01T00:00:00Z")
      },
    )
    val details = ContactDetails(
      crn = offender.crn,
      name = Name("Case", "Person"),
      dateOfBirth = LocalDate.of(1980, 1, 1),
      practitioner = PractitionerDetails(name = Name("Old", "Practitioner"), username = "OLD", email = "old@example.com"),
      events = listOf(Event(1L, CodedDescription("X", "Offence"), sentence = null)),
    )
    fun createCheckin(dueDate: LocalDate) = checkinV2Repository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.CREATED,
        dueDate = dueDate,
        createdAt = Instant.now(),
        createdBy = "SYSTEM",
      ),
    )
    val first = createCheckin(LocalDate.now().minusDays(1))
    val second = createCheckin(LocalDate.now())
    val request = SubmitCheckinRequest(survey = mapOf("version" to SurveyVersion.V20260416Questions.version))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(details)
    whenever(s3UploadService.isCheckinVideoUploaded(any())).thenReturn(true)
    val gateway: NotifyGatewayService = mock()
    whenever(gateway.send(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID())
    val orchestrator = NotificationOrchestratorService(
      notificationPersistenceService, gateway, mock(), mock(), mock(), ndiliusApiClient,
      appConfig, clock, Duration.ofDays(3), mock(),
    )

    try {
      checkinService.submitCheckin(first.uuid, request)
      assertEquals(Instant.parse("2020-01-01T00:00:00Z"), offenderRepository.findById(offender.id).orElseThrow().updatedAt)
      val firstEvent = argumentCaptor<CheckinSubmittedEvent>()
      verify(notificationService, timeout(5000)).sendCheckinSubmittedNotifications(firstEvent.capture())
      orchestrator.sendCheckinSubmittedNotifications(firstEvent.firstValue)
      val oldNotification = genericNotificationRepository.findAll()
        .single { it.offenderId == offender.id && it.recipientType == "PRACTITIONER" }
      assertEquals("OLD", oldNotification.practitionerId)

      val reallocated = details.copy(practitioner = PractitionerDetails(name = Name("New", "Practitioner"), username = "NEW", email = "new@example.com"))
      whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(reallocated)
      checkinService.submitCheckin(second.uuid, request)

      val saved = offenderRepository.findById(offender.id).orElseThrow()
      assertEquals("NEW", saved.practitionerId)
      val updatedAt = saved.updatedAt
      val audits = eventAuditRepository.findByCrnOrderByOccurredAt(setOf(offender.crn))
        .filter { it.checkinUuid == first.uuid || it.checkinUuid == second.uuid }
        .associateBy { it.checkinUuid }
      assertEquals("OLD", audits[first.uuid]?.practitionerId)
      assertEquals("NEW", audits[second.uuid]?.practitionerId)

      val submittedEvents = argumentCaptor<CheckinSubmittedEvent>()
      verify(notificationService, timeout(5000).times(2)).sendCheckinSubmittedNotifications(submittedEvents.capture())
      orchestrator.sendCheckinSubmittedNotifications(submittedEvents.allValues.single { it.checkin.uuid == second.uuid })

      assertEquals("OLD", genericNotificationRepository.findById(oldNotification.id).orElseThrow().practitionerId)
      val notificationIds = genericNotificationRepository.findAll()
        .filter { it.offenderId == offender.id && it.recipientType == "PRACTITIONER" }
        .mapNotNull { it.practitionerId }.toSet()
      assertEquals(setOf("OLD", "NEW"), notificationIds)
      val personalisation = argumentCaptor<Map<String, String>>()
      verify(gateway).send(eq("EMAIL"), any(), eq("new@example.com"), personalisation.capture(), any())
      assertEquals("New", personalisation.firstValue["practitionerName"])
      verify(gateway, times(1)).send(eq("EMAIL"), any(), eq("old@example.com"), any(), any())

      checkinService.getCheckin(second.uuid, includePersonalDetails = true)
      assertEquals(updatedAt, offenderRepository.findById(offender.id).orElseThrow().updatedAt)
      whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(reallocated.copy(practitioner = null))
      checkinService.getCheckin(second.uuid, includePersonalDetails = true)
      val unallocated = offenderRepository.findById(offender.id).orElseThrow()
      assertEquals("NEW", unallocated.practitionerId)
      assertEquals(updatedAt, unallocated.updatedAt)
      verify(ndiliusApiClient, times(4)).getContactDetails(offender.crn)
    } finally {
      eventAuditRepository.deleteAll(eventAuditRepository.findByCrnOrderByOccurredAt(setOf(offender.crn)))
    }
  }

  @Test
  fun `expiry batch persists the new practitioner before notification and audit`() {
    val offender = offenderRepository.save(
      offenderTemplate.copy(crn = "X987651", practitionerId = "OLD").toEntity(),
    )
    checkinV2Repository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.CREATED,
        dueDate = LocalDate.now().minusDays(5),
        createdAt = Instant.now(),
        createdBy = "SYSTEM",
      ),
    )
    val details = ContactDetails(
      crn = offender.crn,
      name = Name("Case", "Person"),
      dateOfBirth = LocalDate.of(1980, 1, 1),
      practitioner = PractitionerDetails(name = Name("New", "Practitioner"), username = "NEW", email = "new@example.com"),
      events = listOf(Event(1L, CodedDescription("X", "Offence"), sentence = null)),
    )
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(offender.crn))).thenReturn(listOf(details))

    try {
      checkinExpiryJob.process()

      assertEquals("NEW", offenderRepository.findById(offender.id).orElseThrow().practitionerId)
      val expired = argumentCaptor<OffenderCheckin>()
      verify(notificationService).sendCheckinExpiredNotifications(expired.capture(), eq(details))
      assertEquals("NEW", expired.firstValue.offender.practitionerId)
      assertEquals("NEW", eventAuditRepository.findByCrnOrderByOccurredAt(setOf(offender.crn)).single().practitionerId)
      verify(ndiliusApiClient, times(1)).getContactDetailsForMultiple(listOf(offender.crn))
    } finally {
      eventAuditRepository.deleteAll(eventAuditRepository.findByCrnOrderByOccurredAt(setOf(offender.crn)))
    }
  }

  @Test
  fun `getCheckin - with personal details - returns checkin with personal details`() {
    val offender = offenderRepository.save(
      offenderTemplate.copy(crn = "X987654", practitionerId = "PRACT002").toEntity(),
    )

    val checkinUuid = UUID.randomUUID()
    checkinV2Repository.save(
      OffenderCheckin(
        uuid = checkinUuid,
        offender = offender,
        status = CheckinStatus.CREATED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
      ),
    )
    whenever(s3UploadService.isCheckinVideoUploaded(any())).thenReturn(true)
    checkinService.submitCheckin(checkinUuid, SubmitCheckinRequest(survey = mapOf("version" to SurveyVersion.V20260416Questions.version)))

    val contactDetails = ContactDetails(
      crn = offender.crn,
      name = uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name(forename = "John", surname = "Doe"),
      email = "john@example.com",
      mobile = "07700900000",
      dateOfBirth = LocalDate.of(1980, 1, 1),
    )
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)

    val result = checkinService.getCheckin(checkinUuid, includePersonalDetails = true)

    assertEquals(checkinUuid, result.uuid)
    assertNotNull(result.personalDetails)
    assertEquals("John", result.personalDetails?.name?.forename)
    assertEquals("Doe", result.personalDetails?.name?.surname)
  }

  @Test
  fun `getCheckin - with flagged survey answers - calculates flags correctly`() {
    val offender = offenderRepository.save(
      offenderTemplate.copy(crn = "X234567", practitionerId = "PRACT001").toEntity(),
    )

    val checkin = checkinV2Repository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.CREATED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
      ),
    )

    whenever(s3UploadService.isCheckinVideoUploaded(any())).thenReturn(true)
    val submissionResult = checkinService.submitCheckin(
      checkin.uuid,
      SubmitCheckinRequest(
        survey = mapOf(
          "version" to SurveyVersion.V20250710pilot.version,
          "mentalHealth" to "STRUGGLING",
          "callback" to "YES",
          "assistance" to listOf("HELP"),
        ),
      ),
    )

    val result = checkinService.getCheckin(checkin.uuid, includePersonalDetails = false)

    assertEquals(3, result.flaggedResponses.size)
    assertTrue(result.flaggedResponses.contains("mentalHealth"))
    assertTrue(result.flaggedResponses.contains("callback"))
    assertTrue(result.flaggedResponses.contains("assistance"))
  }

  @Test
  fun `getCheckin - with non-flagged survey answers - returns empty flaggedResponses`() {
    val offender = offenderRepository.save(offenderTemplate.copy(crn = "X345678").toEntity())
    val checkin = checkinV2Repository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.CREATED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
      ),
    )

    whenever(s3UploadService.isCheckinVideoUploaded(any())).thenReturn(true)
    checkinService.submitCheckin(
      checkin.uuid,
      SubmitCheckinRequest(
        survey = mapOf(
          "version" to SurveyVersion.V20250710pilot.version,
          "mentalHealth" to "GREAT",
          "callback" to "NO",
          "assistance" to listOf("NO_HELP"),
        ),
      ),
    )

    val result = checkinService.getCheckin(checkin.uuid, includePersonalDetails = false)

    assertNotNull(result.flaggedResponses)
    assertTrue(result.flaggedResponses.isEmpty(), "Expected flaggedResponses to be empty, but found: ${result.flaggedResponses}")
  }

  @Test
  fun `annotateCheckin - happy path`() {
    val offender = offenderRepository.save(offenderTemplate.copy(crn = "X345678").toEntity())
    val checkin = checkinV2Repository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.CREATED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
      ),
    )

    whenever(s3UploadService.isCheckinVideoUploaded(any())).thenReturn(true)
    checkinService.submitCheckin(checkin.uuid, SubmitCheckinRequest(survey = mapOf("version" to SurveyVersion.V20250710pilot.version)))

    val reviewResult = checkinService.reviewCheckin(
      checkin.uuid,
      ReviewCheckinRequest(
        reviewedBy = offender.practitionerId,
        manualIdCheck = ManualIdVerificationResult.MATCH,
        notes = "Test note",
      ),
    )
    assertEquals(CheckinStatus.REVIEWED, reviewResult.status)

    val annotationResult = checkinService.annotateCheckin(
      checkin.uuid,
      AnnotateCheckinRequest(
        updatedBy = offender.practitionerId,
        notes = "Looking good",
        sensitive = false,
      ),
    )
    assertEquals(CheckinStatus.REVIEWED, annotationResult.status)

    val result = checkinService.getCheckin(checkin.uuid, includePersonalDetails = false)
    result.checkinLogs.logs.first().let {
      assertEquals(LogEntryType.OFFENDER_CHECKIN_ANNOTATED, it.logEntryType)
      assertEquals("Looking good", it.notes)
    }

    val annotationEvent = offenderEventLogRepository.findAll().first { it.logEntryType == LogEntryType.OFFENDER_CHECKIN_ANNOTATED && it.checkin == checkin.id }
    // note: we don't care about the status of the outbox item, just that it's there
    outboxItemRepository.findByTypeAndEntityId(OutboxItemType.CHECKIN_ANNOTATED, annotationEvent.id).orElseThrow()
  }
}
