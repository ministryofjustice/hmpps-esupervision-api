package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AnnotateCheckinV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.LogEntryType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ReviewCheckinV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SubmitCheckinV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SurveyVersion
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.OffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CheckinV2ServiceIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var checkinV2Service: CheckinV2Service

  @Autowired
  private lateinit var offenderV2Repository: OffenderV2Repository

  @Autowired
  private lateinit var checkinV2Repository: OffenderCheckinV2Repository

  @Autowired
  private lateinit var offenderEventLogV2Repository: OffenderEventLogV2Repository

  @Autowired
  private lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired
  private lateinit var genericNotificationV2Repository: GenericNotificationV2Repository

  @MockitoBean
  private lateinit var ndiliusApiClient: INdiliusApiClient

  @MockitoBean
  private lateinit var notificationV2Service: NotificationV2Service

  @MockitoBean
  private lateinit var s3UploadService: S3UploadService

  @MockitoBean
  private lateinit var offenderIdVerifier: OffenderIdVerifier

  @MockitoBean
  private lateinit var checkinCreationService: CheckinCreationService

  @AfterEach
  fun tearDown() {
    genericNotificationV2Repository.deleteAll()
    offenderEventLogV2Repository.deleteAll()
    checkinV2Repository.deleteAll()
    offenderV2Repository.deleteAll()
    outboxItemRepository.deleteAll()
    reset(s3UploadService)
  }

  @Test
  fun `getCheckin - happy path - returns checkin details`() {
    val offender = offenderTemplate.copy().toEntity()
    offenderV2Repository.save(offender)

    // we want some content in the checkin logs, so we add a CHECKIN_NOT_SUBMITTED event and
    // set the checkin status to match
    val checkin = checkinV2Repository.save(
      OffenderCheckinV2(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinV2Status.EXPIRED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
      ),
    )

    offenderEventLogV2Repository.save(
      OffenderEventLogV2(
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

    val result = checkinV2Service.getCheckin(checkin.uuid, includePersonalDetails = false)

    assertEquals(checkin.uuid, result.uuid)
    assertEquals(offender.crn, result.crn)
    assertEquals(CheckinV2Status.EXPIRED, result.status)
    assertNotNull(result.videoUrl)
    assertNotNull(result.snapshotUrl)
    assertNull(result.personalDetails)
    assertEquals(LogEntryType.OFFENDER_CHECKIN_NOT_SUBMITTED, result.checkinLogs.logs[0].logEntryType)
  }

  @Test
  fun `getCheckin - with personal details - returns checkin with personal details`() {
    val offender = offenderV2Repository.save(
      offenderTemplate.copy(crn = "X987654", practitionerId = "PRACT002").toEntity(),
    )

    val checkinUuid = UUID.randomUUID()
    checkinV2Repository.save(
      OffenderCheckinV2(
        uuid = checkinUuid,
        offender = offender,
        status = CheckinV2Status.CREATED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
      ),
    )
    whenever(s3UploadService.isCheckinVideoUploaded(any())).thenReturn(true)
    checkinV2Service.submitCheckin(checkinUuid, SubmitCheckinV2Request(survey = mapOf("version" to SurveyVersion.V20260416Questions.version)))

    val contactDetails = ContactDetails(
      crn = offender.crn,
      name = uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name(forename = "John", surname = "Doe"),
      email = "john@example.com",
      mobile = "07700900000",
    )
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)

    val result = checkinV2Service.getCheckin(checkinUuid, includePersonalDetails = true)

    assertEquals(checkinUuid, result.uuid)
    assertNotNull(result.personalDetails)
    assertEquals("John", result.personalDetails?.name?.forename)
    assertEquals("Doe", result.personalDetails?.name?.surname)
  }

  @Test
  fun `getCheckin - with flagged survey answers - calculates flags correctly`() {
    val offender = offenderV2Repository.save(
      offenderTemplate.copy(crn = "X234567", practitionerId = "PRACT001").toEntity(),
    )

    val checkin = checkinV2Repository.save(
      OffenderCheckinV2(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinV2Status.CREATED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
      ),
    )

    whenever(s3UploadService.isCheckinVideoUploaded(any())).thenReturn(true)
    val submissionResult = checkinV2Service.submitCheckin(
      checkin.uuid,
      SubmitCheckinV2Request(
        survey = mapOf(
          "version" to SurveyVersion.V20250710pilot.version,
          "mentalHealth" to "STRUGGLING",
          "callback" to "YES",
          "assistance" to listOf("HELP"),
        ),
      ),
    )

    val result = checkinV2Service.getCheckin(checkin.uuid, includePersonalDetails = false)

    assertEquals(3, result.flaggedResponses.size)
    assertTrue(result.flaggedResponses.contains("mentalHealth"))
    assertTrue(result.flaggedResponses.contains("callback"))
    assertTrue(result.flaggedResponses.contains("assistance"))
  }

  @Test
  fun `getCheckin - with non-flagged survey answers - returns empty flaggedResponses`() {
    val offender = offenderV2Repository.save(offenderTemplate.copy(crn = "X345678").toEntity())
    val checkin = checkinV2Repository.save(
      OffenderCheckinV2(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinV2Status.CREATED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
      ),
    )

    whenever(s3UploadService.isCheckinVideoUploaded(any())).thenReturn(true)
    checkinV2Service.submitCheckin(
      checkin.uuid,
      SubmitCheckinV2Request(
        survey = mapOf(
          "version" to SurveyVersion.V20250710pilot.version,
          "mentalHealth" to "GREAT",
          "callback" to "NO",
          "assistance" to listOf("NO_HELP"),
        ),
      ),
    )

    val result = checkinV2Service.getCheckin(checkin.uuid, includePersonalDetails = false)

    assertNotNull(result.flaggedResponses)
    assertTrue(result.flaggedResponses.isEmpty(), "Expected flaggedResponses to be empty, but found: ${result.flaggedResponses}")
  }

  @Test
  fun `annotateCheckin - happy path`() {
    val offender = offenderV2Repository.save(offenderTemplate.copy(crn = "X345678").toEntity())
    val checkin = checkinV2Repository.save(
      OffenderCheckinV2(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinV2Status.CREATED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = offender.practitionerId,
      ),
    )

    whenever(s3UploadService.isCheckinVideoUploaded(any())).thenReturn(true)
    checkinV2Service.submitCheckin(checkin.uuid, SubmitCheckinV2Request(survey = mapOf("version" to SurveyVersion.V20250710pilot.version)))

    val reviewResult = checkinV2Service.reviewCheckin(
      checkin.uuid,
      ReviewCheckinV2Request(
        reviewedBy = offender.practitionerId,
        manualIdCheck = ManualIdVerificationResult.MATCH,
        notes = "Test note",
      ),
    )
    assertEquals(CheckinV2Status.REVIEWED, reviewResult.status)

    val annotationResult = checkinV2Service.annotateCheckin(
      checkin.uuid,
      AnnotateCheckinV2Request(
        updatedBy = offender.practitionerId,
        notes = "Looking good",
        sensitive = false,
      ),
    )
    assertEquals(CheckinV2Status.REVIEWED, annotationResult.status)

    val result = checkinV2Service.getCheckin(checkin.uuid, includePersonalDetails = false)
    result.checkinLogs.logs.first().let {
      assertEquals(LogEntryType.OFFENDER_CHECKIN_ANNOTATED, it.logEntryType)
      assertEquals("Looking good", it.notes)
    }

    val annotationEvent = offenderEventLogV2Repository.findAll().first { it.logEntryType == LogEntryType.OFFENDER_CHECKIN_ANNOTATED && it.checkin == checkin.id }
    // note: we don't care about the status of the outbox item, just that it's there
    outboxItemRepository.findByTypeAndEntityId(OutboxItemType.CHECKIN_ANNOTATED, annotationEvent.id).orElseThrow()
  }
}
