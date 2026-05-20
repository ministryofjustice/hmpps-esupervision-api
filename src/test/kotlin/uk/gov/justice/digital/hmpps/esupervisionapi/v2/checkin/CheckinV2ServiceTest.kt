package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jdk.javadoc.internal.doclets.formats.html.markup.RawHtml.comment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.rekognition.model.AuditImage
import software.amazon.awssdk.services.rekognition.model.GetFaceLivenessSessionResultsResponse
import software.amazon.awssdk.services.rekognition.model.RekognitionException
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AnnotateCheckinV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinReviewInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.LogEntryType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ReviewCheckinV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SubmitCheckinV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.LivenessResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.FacialRecognitionOutcome
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.LivenessCredentialsProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.LivenessSessionService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.OffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.S3ObjectCoordinate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CheckinV2ServiceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-03T10:00:00Z"), ZoneId.of("UTC"))
  private val checkinRepository: OffenderCheckinV2Repository = mock()
  private val offenderRepository: OffenderV2Repository = mock()
  private val offenderEventLogRepository: OffenderEventLogV2Repository = mock()
  private val genericNotificationV2Repository: GenericNotificationV2Repository = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val notificationService: NotificationV2Service = mock()
  private val checkinCreationService: CheckinCreationService = mock()
  private val s3UploadService: S3UploadService = mock()
  private val compareFacesService: OffenderIdVerifier = mock()
  private val livenessSessionService: LivenessSessionService = mock()
  private val livenessCredentialsProvider: LivenessCredentialsProvider = mock()
  private val checkinPersistenceService: CheckinPersistenceService = mock()
  private val transactionTemplate: TransactionTemplate = mock()
  private val uploadTtlMinutes = 10L
  private val faceSimilarityThreshold = 80.0f
  private val livenessConfidenceThreshold = 90.0f
  private val eventAuditService: EventAuditV2Service = mock()
  private val objectMapper = jacksonObjectMapper()

  private lateinit var service: CheckinV2Service

  @BeforeEach
  fun setUp() {
    reset(s3UploadService)
    service = CheckinV2Service(
      clock,
      checkinRepository,
      offenderRepository,
      genericNotificationV2Repository,
      offenderEventLogRepository,
      ndiliusApiClient,
      notificationService,
      checkinCreationService,
      s3UploadService,
      compareFacesService,
      livenessSessionService,
      livenessCredentialsProvider,
      checkinPersistenceService,
      transactionTemplate,
      uploadTtlMinutes,
      faceSimilarityThreshold,
      livenessConfidenceThreshold,
      30,
      eventAuditService,
      objectMapper,
      3,
    )

    whenever(s3UploadService.getCheckinSnapshot(any(), any())).thenReturn(URI.create("https://snapshot/1").toURL())
    whenever(s3UploadService.getCheckinVideo(any())).thenReturn(URI.create("https://video/1").toURL())
  }

  @Test
  fun `getCheckin - returns EXPIRED checkin when past due date`() {
    // this is safeguard against the case when for some reason the checkin has not yet been
    // marked as EXPIRED, but the due date has passed
    val dueDate = LocalDate.now(clock).minusDays(4)
    val checkin = OffenderCheckinV2(
      uuid = UUID.randomUUID(),
      offender = createOffender(),
      status = CheckinV2Status.CREATED,
      dueDate = dueDate.minusDays(4),
      createdAt = dueDate.minusDays(4).atStartOfDay(clock.zone).toInstant(),
      createdBy = "SYSTEM",
    )

    whenever(checkinRepository.findByUuid(checkin.uuid)).thenReturn(Optional.of(checkin))
    val result = service.getCheckin(checkin.uuid)
    assertEquals(CheckinV2Status.EXPIRED, result.status)
  }

  @Test
  fun `sendReminder - happy path - triggers notification`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.CREATED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
    )
    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    val contactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      crn = offender.crn,
      name = uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("John", "Doe"),
      email = "john@example.com",
    )
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)
    service.sendReminder(uuid)
    verify(notificationService).sendCheckinReminderNotifications(checkin, contactDetails)
  }

  @Test
  fun `sendReminder - unhappy path - throws 429 when throttled by 30 min restriction to stop reminders being sent again`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.CREATED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
    )
    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(
      genericNotificationV2Repository.hasNotificationBeenSent(
        eq(offender),
        eq(NotificationType.OffenderCheckinReminder.name),
        any(),
      ),
    ).thenReturn(true)

    val exception = assertThrows(ResponseStatusException::class.java) {
      service.sendReminder(uuid)
    }

    assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.statusCode)
    assertTrue(exception.reason!!.contains("wait 30 minutes"))
  }

  @Test
  fun `submitCheckin - happy path - updates status to SUBMITTED`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.CREATED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      checkinStartedAt = clock.instant(), // Identity verified
    )

    val surveyData = mapOf("question1" to "answer1")
    val request = SubmitCheckinV2Request(survey = surveyData)

    whenever(checkinPersistenceService.findCheckin(uuid)).thenReturn(checkin)
    whenever(s3UploadService.isCheckinVideoUploaded(checkin)).thenReturn(true)
    whenever(s3UploadService.isSetupPhotoUploaded(offender)).thenReturn(false)
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(null)

    val result = service.submitCheckin(uuid, request)

    assertEquals(CheckinV2Status.SUBMITTED, result.status)
    assertNotNull(result.submittedAt)
    assertNull(result.videoUrl, "Submission result should not contain media URLs")
    assertNull(result.snapshotUrl, "Submission result should not contain media URLs")
    verify(checkinPersistenceService).submitCheckinPersist(any(), any())
  }

  @Test
  fun `submitCheckin - unhappy path - checkin not found throws exception`() {
    val uuid = UUID.randomUUID()
    val request = SubmitCheckinV2Request(survey = emptyMap())

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.empty())

    val exception = assertThrows(ResponseStatusException::class.java) {
      service.submitCheckin(uuid, request)
    }

    assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
  }

  @Test
  fun `submitCheckin - unhappy path - already submitted throws exception`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.SUBMITTED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      submittedAt = clock.instant(),
    )

    val request = SubmitCheckinV2Request(survey = emptyMap())

    whenever(checkinPersistenceService.findCheckin(uuid)).thenReturn(checkin)

    val exception = assertThrows(ResponseStatusException::class.java) {
      service.submitCheckin(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
  }

  @Test
  fun `submitCheckin - unhappy path - checkin past due date`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val dueDate = LocalDate.now(clock)
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.CREATED, // e.g., expiry job failed and has not updated it to EXPIRED
      dueDate = dueDate.minusDays(4),
      createdAt = dueDate.atStartOfDay(clock.zone).toInstant(),
      createdBy = "SYSTEM",
      submittedAt = null,
    )

    val request = SubmitCheckinV2Request(survey = emptyMap())

    whenever(checkinPersistenceService.findCheckin(any())).thenReturn(checkin)

    val exception = assertThrows(ResponseStatusException::class.java) {
      service.submitCheckin(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    assertTrue(exception.message!!.contains("past submission date"))
  }

  @Test
  fun `startReview - happy path - marks review as started`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.SUBMITTED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      submittedAt = clock.instant(),
    )

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(null)

    val result = service.startReview(uuid, "PRACT001")

    assertNotNull(result.snapshotUrl)
    verify(checkinRepository).save(any())
  }

  @Test
  fun `reviewCheckin - happy path - completes review and updates status`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.SUBMITTED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      submittedAt = clock.instant(),
      reviewStartedAt = clock.instant(),
      reviewStartedBy = "PRACT001",
    )

    val request = ReviewCheckinV2Request(
      reviewedBy = "PRACT001",
      manualIdCheck = ManualIdVerificationResult.MATCH,
      notes = "Approved",
    )

    whenever(checkinPersistenceService.findCheckin(any())).thenReturn(checkin)
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }

    val result = service.reviewCheckin(uuid, request)

    assertEquals(CheckinV2Status.REVIEWED, result.status)
    assertNotNull(result.reviewedAt)
    assertNull(result.videoUrl)
    assertNull(result.snapshotUrl)
    assertEquals("PRACT001", result.reviewedBy)
    assertEquals(ManualIdVerificationResult.MATCH, result.manualIdCheck)
    verify(checkinPersistenceService).reviewCheckinPersist(any(), any(), any())
    verify(s3UploadService).deleteCheckinSnapshot(uuid, 0)
    verify(s3UploadService).deleteCheckinVideo(uuid)
  }

  @Test
  fun `reviewCheckin - happy path - completes review, manual id check MATCH_WITH_CONCERN`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.SUBMITTED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      submittedAt = clock.instant(),
      reviewStartedAt = clock.instant(),
      reviewStartedBy = "PRACT001",
    )

    val request = ReviewCheckinV2Request(
      reviewedBy = "PRACT001",
      manualIdCheck = ManualIdVerificationResult.MATCH_WITH_CONCERN,
      notes = "Approved",
    )

    whenever(checkinPersistenceService.findCheckin(any())).thenReturn(checkin)
    verify(checkinPersistenceService).reviewCheckinPersist(any(), any(), any())

    val result = service.reviewCheckin(uuid, request)

    assertEquals(CheckinV2Status.REVIEWED, result.status)
    assertNotNull(result.reviewedAt)
    assertNotNull(result.videoUrl)
    assertNotNull(result.snapshotUrl)
    assertEquals("PRACT001", result.reviewedBy)
    assertEquals(ManualIdVerificationResult.MATCH_WITH_CONCERN, result.manualIdCheck)
    verify(checkinPersistenceService).reviewCheckinPersist(any(), any(), any())
    verify(s3UploadService, never()).deleteCheckinVideo(any())
    verify(s3UploadService, never()).deleteCheckinSnapshot(any(), any())
  }

  @Test
  fun `reviewCheckin - unhappy path - non-submitted checkin throws exception`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.CREATED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
    )

    val request = ReviewCheckinV2Request(
      reviewedBy = "PRACT001",
      manualIdCheck = ManualIdVerificationResult.MATCH,
    )

    whenever(checkinPersistenceService.findCheckin(uuid)).thenReturn(checkin)

    val exception = assertThrows(ResponseStatusException::class.java) {
      service.reviewCheckin(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
  }

  @Test
  fun `reviewCheckin - happy path - saves sensitive flag as true when provided`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.SUBMITTED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      submittedAt = clock.instant(),
    )

    val request = ReviewCheckinV2Request(
      reviewedBy = "PRACT001",
      manualIdCheck = ManualIdVerificationResult.MATCH,
      notes = "Contains private health info",
      sensitive = true,
    )

    whenever(checkinPersistenceService.findCheckin(uuid)).thenReturn(checkin)
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }

    val result = service.reviewCheckin(uuid, request)

    assertEquals(true, checkin.sensitive)
    assertEquals(true, result.sensitive)
    verify(checkinPersistenceService).reviewCheckinPersist(
      any(),
      any(),
      eq(
        CheckinReviewInfo(CheckinV2Status.REVIEWED, "Contains private health info", LogEntryType.OFFENDER_CHECKIN_REVIEW_SUBMITTED),
      ),
    )
  }

  @Test
  fun `reviewCheckin - happy path - saves sensitive flag as false when not provided`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.SUBMITTED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      submittedAt = clock.instant(),
    )

    val request = ReviewCheckinV2Request(
      reviewedBy = "PRACT001",
      manualIdCheck = ManualIdVerificationResult.MATCH,
      notes = "Test note",
    )

    whenever(checkinPersistenceService.findCheckin(uuid)).thenReturn(checkin)
    // whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }

    val result = service.reviewCheckin(uuid, request)

    assertEquals(false, checkin.sensitive)
    assertEquals(false, result.sensitive)
    verify(checkinPersistenceService).reviewCheckinPersist(
      argThat { !sensitive },
      any(),
      eq(
        CheckinReviewInfo(CheckinV2Status.REVIEWED, "Test note", LogEntryType.OFFENDER_CHECKIN_REVIEW_SUBMITTED),
      ),
    )
  }

  @Test
  fun `annotateCheckin - happy path - updates checkin sensitive flag as true`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.REVIEWED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      sensitive = false,
    )

    val request = AnnotateCheckinV2Request(
      updatedBy = "PRACT001",
      notes = "Added sensitive medical evidence",
      sensitive = true,
    )

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(offenderEventLogRepository.save(any())).thenAnswer { it.getArgument(0) }

    val result = service.annotateCheckin(uuid, request)

    assertEquals(true, checkin.sensitive)
    assertEquals(true, result.sensitive)
    verify(checkinRepository).save(checkin)

    verify(offenderEventLogRepository).save(
      argThat {
        sensitive == true && comment == "Added sensitive medical evidence"
      },
    )
  }

  @Test
  fun `annotateCheckin - happy path - updates checkin sensitive flag as false when not provided`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.REVIEWED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      sensitive = false,
    )

    val request = AnnotateCheckinV2Request(
      updatedBy = "PRACT001",
      notes = "Test notes",
    )

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(offenderEventLogRepository.save(any())).thenAnswer { it.getArgument(0) }

    val result = service.annotateCheckin(uuid, request)

    assertEquals(false, checkin.sensitive)
    assertEquals(false, result.sensitive)
    verify(checkinRepository, never()).save(checkin)

    verify(offenderEventLogRepository).save(
      argThat {
        sensitive == false && comment == "Test notes"
      },
    )
  }

  @Test
  fun `annotateCheckin - stays false when it was previously marked as false`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.REVIEWED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      sensitive = false,
    )

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(offenderEventLogRepository.save(any())).thenAnswer { it.getArgument(0) }
    service.annotateCheckin(uuid, AnnotateCheckinV2Request("P1", "Note", sensitive = false))
    assertEquals(false, checkin.sensitive)
  }

  @Test
  fun `annotateCheckin - sensitive flag updates to true when it was previously marked as false `() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.REVIEWED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      sensitive = false,
    )

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(offenderEventLogRepository.save(any())).thenAnswer { it.getArgument(0) }

    service.annotateCheckin(uuid, AnnotateCheckinV2Request("P1", "Note", sensitive = true))

    assertEquals(true, checkin.sensitive)
  }

  @Test
  fun `annotateCheckin - sensitive flag remains true when check in was already marked as true`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.REVIEWED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      sensitive = true,
    )

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(offenderEventLogRepository.save(any())).thenAnswer { it.getArgument(0) }
    service.annotateCheckin(uuid, AnnotateCheckinV2Request("P1", "Note", sensitive = false))

    assertEquals(true, checkin.sensitive)
    verify(checkinRepository, never()).save(checkin)
    verify(offenderEventLogRepository).save(
      argThat {
        sensitive == true && comment == "Note"
      },
    )
  }

  @Test
  fun `reviewCheckin - sensitive log entry stays true when check in was already marked as true`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.SUBMITTED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      submittedAt = clock.instant(),
      sensitive = true,
    )

    whenever(checkinPersistenceService.findCheckin(uuid)).thenReturn(checkin)
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(offenderEventLogRepository.save(any())).thenAnswer { it.getArgument(0) }

    val result = service.reviewCheckin(
      uuid,
      ReviewCheckinV2Request(
        reviewedBy = "PRACT001",
        manualIdCheck = ManualIdVerificationResult.MATCH,
        notes = "Some note",
        sensitive = false,
      ),
    )

    assertEquals(true, checkin.sensitive)
    assertEquals(true, result.sensitive)
    verify(checkinPersistenceService).reviewCheckinPersist(any(), any(), any())
  }

  @Test
  fun `createLivenessSession - happy path - sets livenessEnabled to true`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.CREATED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
    )

    assertFalse(checkin.livenessEnabled)

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(livenessSessionService.createSession()).thenReturn(CompletableFuture.completedFuture("session-123"))

    val result = service.createLivenessSession(uuid)

    assertEquals("session-123", result.sessionId)
    assertTrue(checkin.livenessEnabled)
    verify(checkinRepository).save(checkin)
  }

  @Test
  fun `createLivenessSession - unhappy path - checkin not found throws exception`() {
    val uuid = UUID.randomUUID()

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.empty())

    val exception = assertThrows(ResponseStatusException::class.java) {
      service.createLivenessSession(uuid)
    }

    assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
  }

  @Test
  fun `createLivenessSession - unhappy path - non-CREATED checkin throws exception`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.SUBMITTED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      submittedAt = clock.instant(),
    )

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

    val exception = assertThrows(ResponseStatusException::class.java) {
      service.createLivenessSession(uuid)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    assertFalse(checkin.livenessEnabled)
  }

  @Test
  fun `createLivenessSession - clears stale livenessResult, livenessConfidence and autoIdCheck from a prior failed attempt`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.CREATED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      livenessEnabled = true,
      livenessResult = LivenessResult.NOT_LIVE,
      livenessConfidence = 42.0f,
      autoIdCheck = AutomatedIdVerificationResult.NO_MATCH,
      autoIdCheckScore = 71.4f,
    )

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(livenessSessionService.createSession()).thenReturn(CompletableFuture.completedFuture("session-456"))

    service.createLivenessSession(uuid)

    assertTrue(checkin.livenessEnabled)
    assertNull(checkin.livenessResult)
    assertNull(checkin.livenessConfidence)
    assertNull(checkin.autoIdCheck)
    assertNull(checkin.autoIdCheckScore)
  }

  @Test
  fun `createLivenessSession - clears stale livenessResult=LIVE so a later fallback cannot piggyback on a prior pass`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender()
    val checkin = OffenderCheckinV2(
      uuid = uuid,
      offender = offender,
      status = CheckinV2Status.CREATED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
      livenessEnabled = true,
      livenessResult = LivenessResult.LIVE,
      livenessConfidence = 95.0f,
      autoIdCheck = AutomatedIdVerificationResult.MATCH,
      autoIdCheckScore = 96.2f,
    )

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(livenessSessionService.createSession()).thenReturn(CompletableFuture.completedFuture("session-789"))

    service.createLivenessSession(uuid)

    assertTrue(checkin.livenessEnabled)
    assertNull(checkin.livenessResult)
    assertNull(checkin.livenessConfidence)
    assertNull(checkin.autoIdCheck)
    assertNull(checkin.autoIdCheckScore)
  }

  // ----- Failure logging via OffenderEventLogV2 -----

  @Test
  fun `recordLivenessClientFailure - writes a CLIENT_ERROR row carrying the Amplify state`() {
    val uuid = UUID.randomUUID()
    val checkin = createCreatedCheckin(uuid)
    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

    service.recordLivenessClientFailure(uuid, "MULTIPLE_FACES_ERROR")

    verify(offenderEventLogRepository).save(
      argThat<OffenderEventLogV2> {
        val notes = parseNotes(comment)
        logEntryType == LogEntryType.OFFENDER_CHECKIN_LIVENESS_FAILED &&
          notes["result"] == "CLIENT_ERROR" &&
          notes["state"] == "MULTIPLE_FACES_ERROR"
      },
    )
  }

  @Test
  fun `recordLivenessClientFailure - omits state from comment JSON when null`() {
    val uuid = UUID.randomUUID()
    val checkin = createCreatedCheckin(uuid)
    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

    service.recordLivenessClientFailure(uuid, null)

    verify(offenderEventLogRepository).save(
      argThat<OffenderEventLogV2> {
        val notes = parseNotes(comment)
        logEntryType == LogEntryType.OFFENDER_CHECKIN_LIVENESS_FAILED &&
          notes["result"] == "CLIENT_ERROR" &&
          !notes.containsKey("state")
      },
    )
  }

  @Test
  fun `recordLivenessClientFailure - throws NOT_FOUND when checkin does not exist`() {
    val uuid = UUID.randomUUID()
    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.empty())

    val ex = assertThrows(ResponseStatusException::class.java) {
      service.recordLivenessClientFailure(uuid, "TIMEOUT")
    }
    assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    verify(offenderEventLogRepository, never()).save(any())
  }

  @Test
  fun `recordLivenessClientFailure - rejects a checkin that's already submitted`() {
    val uuid = UUID.randomUUID()
    val checkin = createCreatedCheckin(uuid).apply { status = CheckinV2Status.SUBMITTED }
    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

    val ex = assertThrows(ResponseStatusException::class.java) {
      service.recordLivenessClientFailure(uuid, "TIMEOUT")
    }
    assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    verify(offenderEventLogRepository, never()).save(any())
  }

  @Test
  fun `verifyFace - MATCH persists score and writes no failure row`() {
    val uuid = UUID.randomUUID()
    val checkin = createCreatedCheckin(uuid)
    stubFaceMatchPrereqs(checkin)
    whenever(compareFacesService.verifyCheckinImages(any(), eq(faceSimilarityThreshold)))
      .thenReturn(CompletableFuture.completedFuture(FacialRecognitionOutcome(AutomatedIdVerificationResult.MATCH, topSimilarity = 95.5f)))

    service.verifyFace(uuid, numSnapshots = 1)

    assertEquals(AutomatedIdVerificationResult.MATCH, checkin.autoIdCheck)
    assertEquals(95.5f, checkin.autoIdCheckScore)
    verify(offenderEventLogRepository, never()).save(any())
  }

  @Test
  fun `verifyFace - NO_MATCH persists score and writes a failure row with similarity in comment`() {
    val uuid = UUID.randomUUID()
    val checkin = createCreatedCheckin(uuid)
    stubFaceMatchPrereqs(checkin)
    whenever(compareFacesService.verifyCheckinImages(any(), eq(faceSimilarityThreshold)))
      .thenReturn(CompletableFuture.completedFuture(FacialRecognitionOutcome(AutomatedIdVerificationResult.NO_MATCH, topSimilarity = 67.3f)))

    service.verifyFace(uuid, numSnapshots = 1)

    assertEquals(AutomatedIdVerificationResult.NO_MATCH, checkin.autoIdCheck)
    assertEquals(67.3f, checkin.autoIdCheckScore)
    verify(offenderEventLogRepository).save(
      argThat<OffenderEventLogV2> {
        val notes = parseNotes(comment)
        logEntryType == LogEntryType.OFFENDER_CHECKIN_FACE_MATCH_FAILED &&
          notes["result"] == "NO_MATCH" &&
          notesNumber(notes, "similarity") == 67.3f
      },
    )
  }

  @Test
  fun `verifyFace - NO_FACE_DETECTED writes a failure row carrying the AWS errorCode`() {
    val uuid = UUID.randomUUID()
    val checkin = createCreatedCheckin(uuid)
    stubFaceMatchPrereqs(checkin)
    whenever(compareFacesService.verifyCheckinImages(any(), eq(faceSimilarityThreshold)))
      .thenReturn(
        CompletableFuture.completedFuture(
          FacialRecognitionOutcome(
            AutomatedIdVerificationResult.NO_FACE_DETECTED,
            topSimilarity = null,
            errorCode = "InvalidParameterException",
          ),
        ),
      )

    service.verifyFace(uuid, numSnapshots = 1)

    assertEquals(AutomatedIdVerificationResult.NO_FACE_DETECTED, checkin.autoIdCheck)
    assertNull(checkin.autoIdCheckScore)
    verify(offenderEventLogRepository).save(
      argThat<OffenderEventLogV2> {
        val notes = parseNotes(comment)
        logEntryType == LogEntryType.OFFENDER_CHECKIN_FACE_MATCH_FAILED &&
          notes["result"] == "NO_FACE_DETECTED" &&
          notes["errorCode"] == "InvalidParameterException"
      },
    )
  }

  @Test
  fun `verifyFace - ERROR writes a failure row carrying the AWS errorCode`() {
    val uuid = UUID.randomUUID()
    val checkin = createCreatedCheckin(uuid)
    stubFaceMatchPrereqs(checkin)
    whenever(compareFacesService.verifyCheckinImages(any(), eq(faceSimilarityThreshold)))
      .thenReturn(
        CompletableFuture.completedFuture(
          FacialRecognitionOutcome(AutomatedIdVerificationResult.ERROR, topSimilarity = null, errorCode = "ThrottlingException"),
        ),
      )

    service.verifyFace(uuid, numSnapshots = 1)

    assertEquals(AutomatedIdVerificationResult.ERROR, checkin.autoIdCheck)
    assertNull(checkin.autoIdCheckScore)
    verify(offenderEventLogRepository).save(
      argThat<OffenderEventLogV2> {
        val notes = parseNotes(comment)
        logEntryType == LogEntryType.OFFENDER_CHECKIN_FACE_MATCH_FAILED &&
          notes["result"] == "ERROR" &&
          notes["errorCode"] == "ThrottlingException"
      },
    )
  }

  @Test
  fun `verifyLiveness - NOT_LIVE writes a liveness failure row carrying the confidence`() {
    val uuid = UUID.randomUUID()
    val sessionId = "session-abc"
    val checkin = createCreatedCheckin(uuid, livenessEnabled = true)
    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(livenessSessionService.getSessionResults(sessionId))
      .thenReturn(CompletableFuture.completedFuture(buildLivenessResponse(sessionId, confidence = 42.5f, withReferenceImage = true)))
    stubFaceMatchPrereqs(checkin)
    whenever(compareFacesService.verifyCheckinImages(any(), eq(faceSimilarityThreshold)))
      .thenReturn(CompletableFuture.completedFuture(FacialRecognitionOutcome(AutomatedIdVerificationResult.MATCH, topSimilarity = 95.0f)))

    service.verifyLiveness(uuid, sessionId)

    verify(offenderEventLogRepository).save(
      argThat<OffenderEventLogV2> {
        val notes = parseNotes(comment)
        logEntryType == LogEntryType.OFFENDER_CHECKIN_LIVENESS_FAILED &&
          notes["result"] == "NOT_LIVE" &&
          notesNumber(notes, "confidence") == 42.5f &&
          notes["sessionId"] == sessionId
      },
    )
  }

  @Test
  fun `verifyLiveness - non-MATCH face match writes a face-match failure row`() {
    val uuid = UUID.randomUUID()
    val sessionId = "session-xyz"
    val checkin = createCreatedCheckin(uuid, livenessEnabled = true)
    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(livenessSessionService.getSessionResults(sessionId))
      .thenReturn(CompletableFuture.completedFuture(buildLivenessResponse(sessionId, confidence = 99.0f, withReferenceImage = true)))
    stubFaceMatchPrereqs(checkin)
    whenever(compareFacesService.verifyCheckinImages(any(), eq(faceSimilarityThreshold)))
      .thenReturn(
        CompletableFuture.completedFuture(
          FacialRecognitionOutcome(AutomatedIdVerificationResult.NO_MATCH, topSimilarity = 51.2f),
        ),
      )

    service.verifyLiveness(uuid, sessionId)

    verify(offenderEventLogRepository).save(
      argThat<OffenderEventLogV2> {
        val notes = parseNotes(comment)
        logEntryType == LogEntryType.OFFENDER_CHECKIN_FACE_MATCH_FAILED &&
          notes["result"] == "NO_MATCH" &&
          notesNumber(notes, "similarity") == 51.2f &&
          notes["sessionId"] == sessionId
      },
    )
    assertEquals(51.2f, checkin.autoIdCheckScore)
  }

  @Test
  fun `verifyLiveness - missing reference image writes a face-match failure row and persists ERROR`() {
    val uuid = UUID.randomUUID()
    val sessionId = "session-no-img"
    val checkin = createCreatedCheckin(uuid, livenessEnabled = true)
    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(livenessSessionService.getSessionResults(sessionId))
      .thenReturn(CompletableFuture.completedFuture(buildLivenessResponse(sessionId, confidence = 99.0f, withReferenceImage = false)))

    val response = service.verifyLiveness(uuid, sessionId)

    assertEquals(AutomatedIdVerificationResult.ERROR, response.result)
    assertEquals(AutomatedIdVerificationResult.ERROR, checkin.autoIdCheck)
    assertNull(checkin.autoIdCheckScore)
    verify(offenderEventLogRepository).save(
      argThat<OffenderEventLogV2> {
        val notes = parseNotes(comment)
        logEntryType == LogEntryType.OFFENDER_CHECKIN_FACE_MATCH_FAILED &&
          notes["result"] == "ERROR" &&
          notes["reason"] == "no reference image from liveness session" &&
          notes["sessionId"] == sessionId
      },
    )
  }

  @Test
  fun `verifyLiveness - Rekognition error during getSessionResults writes a liveness failure row before propagating`() {
    val uuid = UUID.randomUUID()
    val sessionId = "session-bad"
    val checkin = createCreatedCheckin(uuid, livenessEnabled = true)
    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    val rekogError = RekognitionException.builder().message("Service unavailable").build()
    whenever(livenessSessionService.getSessionResults(sessionId))
      .thenReturn(CompletableFuture.failedFuture(rekogError))

    assertThrows(ResponseStatusException::class.java) {
      service.verifyLiveness(uuid, sessionId)
    }

    verify(offenderEventLogRepository).save(
      argThat<OffenderEventLogV2> {
        val notes = parseNotes(comment)
        logEntryType == LogEntryType.OFFENDER_CHECKIN_LIVENESS_FAILED &&
          notes["result"] == "ERROR" &&
          notes["action"] == "get liveness session results" &&
          notes["sessionId"] == sessionId
      },
    )
  }

  @Test
  fun `createLivenessSession - Rekognition error writes a liveness failure row before propagating`() {
    val uuid = UUID.randomUUID()
    val checkin = createCreatedCheckin(uuid)
    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    val rekogError = RekognitionException.builder().message("Throttled").build()
    whenever(livenessSessionService.createSession())
      .thenReturn(CompletableFuture.failedFuture(rekogError))

    assertThrows(ResponseStatusException::class.java) {
      service.createLivenessSession(uuid)
    }

    verify(offenderEventLogRepository).save(
      argThat<OffenderEventLogV2> {
        val notes = parseNotes(comment)
        logEntryType == LogEntryType.OFFENDER_CHECKIN_LIVENESS_FAILED &&
          notes["result"] == "ERROR" &&
          notes["action"] == "create liveness session"
      },
    )
  }

  // ----- Failure-logging test helpers -----

  private fun createCreatedCheckin(uuid: UUID, livenessEnabled: Boolean = false) = OffenderCheckinV2(
    uuid = uuid,
    offender = createOffender(),
    status = CheckinV2Status.CREATED,
    dueDate = LocalDate.now(clock),
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
    livenessEnabled = livenessEnabled,
  )

  /** Mock just enough of `findByUuid` and S3 prereqs to let `performFacialRecognition` reach the verifier. */
  private fun stubFaceMatchPrereqs(checkin: OffenderCheckinV2) {
    whenever(checkinRepository.findByUuid(checkin.uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(s3UploadService.isCheckinSnapshotUploaded(eq(checkin), any())).thenReturn(true)
    whenever(s3UploadService.isSetupPhotoUploaded(eq(checkin.offender))).thenReturn(true)
    whenever(s3UploadService.setupPhotoObjectCoordinate(eq(checkin.offender)))
      .thenReturn(S3ObjectCoordinate("bucket", "setup.jpg"))
    whenever(s3UploadService.checkinObjectCoordinate(eq(checkin), any()))
      .thenReturn(S3ObjectCoordinate("bucket", "snapshot.jpg"))
    whenever(s3UploadService.uploadCheckinSnapshot(eq(checkin), any(), any(), any()))
      .thenReturn(S3ObjectCoordinate("bucket", "uploaded.jpg"))
  }

  /** Parse the JSON the service writes into the comment column so assertions can match on keys/values. */
  private fun parseNotes(comment: String): Map<String, Any?> = objectMapper.readValue(comment)

  /**
   * Pull a numeric value out of parsed notes as a Float. Jackson reads JSON numbers as Double
   * by default; converting back to Float makes comparison with the original Float values clean
   * even when the decimal isn't exactly representable in binary (e.g. 51.2f).
   */
  private fun notesNumber(notes: Map<String, Any?>, key: String): Float? = (notes[key] as? Number)?.toFloat()

  private fun buildLivenessResponse(
    sessionId: String,
    confidence: Float,
    withReferenceImage: Boolean,
  ): GetFaceLivenessSessionResultsResponse {
    val builder = GetFaceLivenessSessionResultsResponse.builder()
      .sessionId(sessionId)
      .confidence(confidence)
      .status("SUCCEEDED")
    if (withReferenceImage) {
      // Tiny non-empty byte array — the service only checks isEmpty().
      builder.referenceImage(AuditImage.builder().bytes(SdkBytes.fromByteArray(byteArrayOf(1, 2, 3))).build())
    }
    return builder.build()
  }

  private fun createOffender() = OffenderV2(
    uuid = UUID.randomUUID(),
    crn = "X123456",
    practitionerId = "PRACT001",
    status = OffenderStatus.VERIFIED,
    firstCheckin = LocalDate.now(clock),
    checkinInterval = CheckinInterval.WEEKLY.duration,
    createdAt = clock.instant(),
    createdBy = "PRACT001",
    updatedAt = clock.instant(),
    contactPreference = ContactPreference.PHONE,
  )
}
