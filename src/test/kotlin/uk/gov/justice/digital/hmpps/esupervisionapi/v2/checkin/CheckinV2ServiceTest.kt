package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ReviewCheckinV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SubmitCheckinV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.OffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

class CheckinV2ServiceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-03T10:00:00Z"), ZoneId.of("UTC"))
  private val checkinRepository: OffenderCheckinV2Repository = mock()
  private val offenderRepository: OffenderV2Repository = mock()
  private val offenderEventLogRepository: OffenderEventLogV2Repository = mock()
  private val ndiliusApiClient: NdiliusApiClient = mock()
  private val notificationService: NotificationV2Service = mock()
  private val checkinCreationService: CheckinCreationService = mock()
  private val s3UploadService: S3UploadService = mock()
  private val compareFacesService: OffenderIdVerifier = mock()
  private val eventAuditRepository: EventAuditV2Repository = mock()
  private val uploadTtlMinutes = 10L
  private val faceSimilarityThreshold = 80.0f

  private lateinit var service: CheckinV2Service

  @BeforeEach
  fun setUp() {
    reset(s3UploadService)
    service = CheckinV2Service(
      clock,
      checkinRepository,
      offenderRepository,
      offenderEventLogRepository,
      ndiliusApiClient,
      notificationService,
      checkinCreationService,
      s3UploadService,
      compareFacesService,
      eventAuditRepository,
      uploadTtlMinutes,
      faceSimilarityThreshold,
    )

    whenever(s3UploadService.getCheckinSnapshot(any(), any())).thenReturn(URI.create("https://snapshot/1").toURL())
    whenever(s3UploadService.getCheckinVideo(any())).thenReturn(URI.create("https://video/1").toURL())
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

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(s3UploadService.isCheckinVideoUploaded(checkin)).thenReturn(true)
    whenever(s3UploadService.isSetupPhotoUploaded(offender)).thenReturn(false)
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(null)

    val result = service.submitCheckin(uuid, request)

    assertEquals(CheckinV2Status.SUBMITTED, result.status)
    assertNotNull(result.submittedAt)
    assertNull(result.videoUrl, "Submission result should not contain media URLs")
    assertNull(result.snapshotUrl, "Submission result should not contain media URLs")
    verify(checkinRepository).save(any())
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

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

    val exception = assertThrows(ResponseStatusException::class.java) {
      service.submitCheckin(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
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

    assertNotNull(result.reviewStartedAt)
    assertNotNull(result.reviewStartedBy)
    assertNotNull(result.snapshotUrl)
    assertEquals("PRACT001", result.reviewStartedBy)
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

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    whenever(checkinRepository.save(any())).thenAnswer { it.getArgument(0) }

    val result = service.reviewCheckin(uuid, request)

    assertEquals(CheckinV2Status.REVIEWED, result.status)
    assertNotNull(result.reviewedAt)
    assertNotNull(result.videoUrl)
    assertNotNull(result.snapshotUrl)
    assertEquals("PRACT001", result.reviewedBy)
    assertEquals(ManualIdVerificationResult.MATCH, result.manualIdCheck)
    verify(checkinRepository).save(any())
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

    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

    val exception = assertThrows(ResponseStatusException::class.java) {
      service.reviewCheckin(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
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
  )
}
