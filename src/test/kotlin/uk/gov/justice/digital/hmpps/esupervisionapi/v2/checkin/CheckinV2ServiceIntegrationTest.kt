package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.LogEntryType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.OffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.net.URI
import java.time.Duration
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

  @BeforeEach
  fun setUp() {
    offenderEventLogV2Repository.deleteAll()
    checkinV2Repository.deleteAll()
    offenderV2Repository.deleteAll()
  }

  @Test
  fun `getCheckin - happy path - returns checkin details`() {
    val offender = offenderV2Repository.save(
      OffenderV2(
        uuid = UUID.randomUUID(),
        crn = "X123456",
        practitionerId = "PRACT001",
        firstCheckin = LocalDate.now(),
        checkinInterval = Duration.ofDays(7),
        createdAt = Instant.now(),
        createdBy = "SYSTEM",
        updatedAt = Instant.now(),
        contactPreference = ContactPreference.PHONE,
      ),
    )

    // we want some content in the checkin logs, so we add a CHECKIN_NOT_SUBMITTED event and
    // set the checkin status to match
    val checkin = checkinV2Repository.save(
      OffenderCheckinV2(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinV2Status.EXPIRED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = "PRACT001",
      ),
    )

    offenderEventLogV2Repository.save(
      OffenderEventLogV2(
        "he forgot",
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
      OffenderV2(
        uuid = UUID.randomUUID(),
        crn = "X987654",
        practitionerId = "PRACT002",
        firstCheckin = LocalDate.now(),
        checkinInterval = Duration.ofDays(7),
        createdAt = Instant.now(),
        createdBy = "SYSTEM",
        updatedAt = Instant.now(),
        contactPreference = ContactPreference.PHONE,
      ),
    )

    val checkinUuid = UUID.randomUUID()
    checkinV2Repository.save(
      OffenderCheckinV2(
        uuid = checkinUuid,
        offender = offender,
        status = CheckinV2Status.SUBMITTED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = "PRACT002",
      ),
    )

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
      OffenderV2(
        uuid = UUID.randomUUID(),
        crn = "X234567",
        practitionerId = "PRACT001",
        firstCheckin = LocalDate.now(),
        checkinInterval = Duration.ofDays(7),
        createdAt = Instant.now(),
        createdBy = "SYSTEM",
        updatedAt = Instant.now(),
        contactPreference = ContactPreference.PHONE,
      ),
    )

    val checkin = checkinV2Repository.save(
      OffenderCheckinV2(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinV2Status.SUBMITTED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = "PRACT001",
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot",
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
    val offender = offenderV2Repository.save(
      OffenderV2(
        uuid = UUID.randomUUID(),
        crn = "X345678",
        practitionerId = "PRACT001",
        firstCheckin = LocalDate.now(),
        checkinInterval = Duration.ofDays(7),
        createdAt = Instant.now(),
        createdBy = "SYSTEM",
        updatedAt = Instant.now(),
        contactPreference = ContactPreference.PHONE,
      ),
    )

    val checkin = checkinV2Repository.save(
      OffenderCheckinV2(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinV2Status.SUBMITTED,
        dueDate = LocalDate.now(),
        createdAt = Instant.now(),
        createdBy = "PRACT001",
        surveyResponse = mapOf(
          "version" to "2025-07-10@pilot",
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
}
