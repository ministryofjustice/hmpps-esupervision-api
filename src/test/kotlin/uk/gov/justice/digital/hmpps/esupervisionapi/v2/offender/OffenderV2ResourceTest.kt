package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

class OffenderV2ResourceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-10T10:00:00Z"), ZoneId.of("UTC"))
  private val offenderRepository: OffenderV2Repository = mock()
  private val s3UploadService: S3UploadService = mock()
  private val checkinCreationService: CheckinCreationService = mock()
  private val eventAuditV2Service: EventAuditV2Service = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val notificationV2Service: NotificationV2Service = mock()
  private val checkinRepository: OffenderCheckinV2Repository = mock()

  private lateinit var resource: OffenderV2Resource

  @BeforeEach
  fun setUp() {
    resource = OffenderV2Resource(
      offenderRepository,
      s3UploadService,
      clock,
      checkinCreationService,
      eventAuditV2Service,
      ndiliusApiClient,
      notificationV2Service,
      checkinRepository,
    )
  }

  // ========================================
  // Deactivate Tests
  // ========================================

  @Test
  fun `deactivateOffender - happy path - changes VERIFIED to INACTIVE`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.VERIFIED)
    val request = DeactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "No longer on supervision",
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }

    val result = resource.deactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(OffenderStatus.INACTIVE, result.body?.status)
    assertEquals(uuid, result.body?.uuid)
    verify(offenderRepository).save(any())
  }

  @Test
  fun `deactivateOffender - offender not found - throws 404`() {
    val uuid = UUID.randomUUID()
    val request = DeactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Test",
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.empty())

    val exception = assertThrows(ResponseStatusException::class.java) {
      resource.deactivateOffender(uuid, request)
    }

    assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
  }

  @Test
  fun `deactivateOffender - offender already INACTIVE - throws 400`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INACTIVE)
    val request = DeactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Test",
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))

    val exception = assertThrows(ResponseStatusException::class.java) {
      resource.deactivateOffender(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
  }

  @Test
  fun `deactivateOffender - offender in INITIAL status - throws 400`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INITIAL)
    val request = DeactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Test",
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))

    val exception = assertThrows(ResponseStatusException::class.java) {
      resource.deactivateOffender(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
  }

  @Test
  fun `deactivateOffender - happy path - returns photo URL for INACTIVE offender`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.VERIFIED)
    val request = DeactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "No longer on supervision",
    )

    val presignedUrl = URI("https://s3.amazonaws.com/bucket/photo.jpg?presigned=true").toURL()
    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }
    // mock s3
    whenever(s3UploadService.getOffenderPhoto(any())).thenReturn(presignedUrl)

    val result = resource.deactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(OffenderStatus.INACTIVE, result.body?.status)
    assertEquals(uuid, result.body?.uuid)
    assertEquals("https://s3.amazonaws.com/bucket/photo.jpg?presigned=true", result.body?.photoUrl)
  }

  @Test
  fun `deactivateOffender - happy path - changes any pending check ins to CANCELLED`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.VERIFIED)
    val request = DeactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "No longer on supervision",
    )

    val pendingCheckin = OffenderCheckinV2(
      uuid = UUID.randomUUID(),
      offender = offender,
      status = CheckinV2Status.CREATED,
      dueDate = LocalDate.now(clock),
      createdAt = clock.instant(),
      createdBy = "PRACT001",
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(offenderRepository.save(any<OffenderV2>())).thenAnswer { it.getArgument(0) }

    whenever(checkinRepository.findAllByOffenderAndStatus(offender, CheckinV2Status.CREATED))
      .thenReturn(listOf(pendingCheckin))

    val result = resource.deactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(OffenderStatus.INACTIVE, result.body?.status)
    assertEquals(CheckinV2Status.CANCELLED, pendingCheckin.status)

    verify(offenderRepository).save(offender)
  }

  // ========================================
  // Reactivate Tests
  // ========================================

  @Test
  fun `reactivateOffender - happy path - changes INACTIVE to VERIFIED, creates check in and sends notification`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INACTIVE)
    val request = ReactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Back on supervision",
    )
    val checkin = mock<uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2>()
    val contactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      offender.crn,
      uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("John", "Doe"),
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(checkinCreationService.createCheckin(any(), any(), any())).thenReturn(checkin)
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)

    val result = resource.reactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(OffenderStatus.VERIFIED, result.body?.status)

    verify(offenderRepository).save(any())
    verify(checkinCreationService).createCheckin(eq(uuid), eq(offender.firstCheckin), eq("PRACT001"))
    verify(notificationV2Service).sendReactivationCompletedNotifications(eq(offender), eq(contactDetails))
  }

  @Test
  fun `reactivateOffender - with schedule update - applies new schedule before creating check in`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INACTIVE)
    val newDate = LocalDate.now(clock).plusDays(7)
    val request = ReactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Restart with new schedule",
      checkinSchedule = CheckinScheduleUpdateRequest(
        requestedBy = "PRACT001",
        firstCheckin = newDate,
        checkinInterval = CheckinInterval.TWO_WEEKS,
      ),
    )
    val checkin = mock<uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2>()

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(checkinCreationService.createCheckin(any(), any(), any())).thenReturn(checkin)

    val result = resource.reactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(newDate, result.body?.firstCheckin)
    assertEquals(CheckinInterval.TWO_WEEKS, result.body?.checkinInterval)
    verify(checkinCreationService).createCheckin(eq(uuid), eq(newDate), eq("PRACT001"))
  }

  @Test
  fun `reactivateOffender - offender not found - throws 404`() {
    val uuid = UUID.randomUUID()
    val request = ReactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Test",
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.empty())

    val exception = assertThrows(ResponseStatusException::class.java) {
      resource.reactivateOffender(uuid, request)
    }

    assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
  }

  @Test
  fun `reactivateOffender - offender already VERIFIED - throws 400`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.VERIFIED)
    val request = ReactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Test",
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))

    val exception = assertThrows(ResponseStatusException::class.java) {
      resource.reactivateOffender(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
  }

  @Test
  fun `reactivateOffender - offender in INITIAL status - throws 400`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INITIAL)
    val request = ReactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Test",
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))

    val exception = assertThrows(ResponseStatusException::class.java) {
      resource.reactivateOffender(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
  }

  @Test
  fun `reactivateOffender - happy path - returns photo URL for VERIFIED offender`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INACTIVE)
    val request = ReactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Restarting supervision",
    )

    val presignedUrl = URI("https://s3.amazonaws.com/bucket/photo.jpg?presigned=true").toURL()
    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }
    // mock s3
    whenever(s3UploadService.getOffenderPhoto(any())).thenReturn(presignedUrl)

    val result = resource.reactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(OffenderStatus.VERIFIED, result.body?.status)
    assertEquals(uuid, result.body?.uuid)
    assertEquals("https://s3.amazonaws.com/bucket/photo.jpg?presigned=true", result.body?.photoUrl)
  }

  // ========================================
  // Upload Location Tests
  // ========================================

  @Test
  fun `getPhotoUploadLocation - happy path - returns presigned URL for VERIFIED offender`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.VERIFIED)
    val presignedUrl = URI("https://s3.amazonaws.com/bucket/photo.jpg?presigned=true").toURL()

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(s3UploadService.generatePresignedUploadUrl(eq(offender), eq("image/jpeg"), any<Duration>()))
      .thenReturn(presignedUrl)

    val result = resource.getPhotoUploadLocation(uuid, "image/jpeg")

    assertEquals(HttpStatus.OK, result.statusCode)
    assertNotNull(result.body?.locationInfo)
    assertEquals(presignedUrl.toString(), result.body?.locationInfo?.url.toString())
    assertEquals("image/jpeg", result.body?.locationInfo?.contentType)
  }

  @Test
  fun `getPhotoUploadLocation - offender not found - returns 404`() {
    val uuid = UUID.randomUUID()

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.empty())

    val result = resource.getPhotoUploadLocation(uuid, "image/jpeg")

    assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
  }

  @Test
  fun `getPhotoUploadLocation - offender INACTIVE - returns 400 with error message`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INACTIVE)

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))

    val result = resource.getPhotoUploadLocation(uuid, "image/jpeg")

    assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
    assertNull(result.body?.locationInfo)
    assertNotNull(result.body?.errorMessage)
  }

  @Test
  fun `getPhotoUploadLocation - offender INITIAL - returns 400 with error message`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INITIAL)

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))

    val result = resource.getPhotoUploadLocation(uuid, "image/jpeg")

    assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
    assertNull(result.body?.locationInfo)
    assertNotNull(result.body?.errorMessage)
  }

  @Test
  fun `getPhotoUploadLocation - unsupported content type - returns 400 with error message`() {
    val uuid = UUID.randomUUID()

    val result = resource.getPhotoUploadLocation(uuid, "application/pdf")

    assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
    assertNull(result.body?.locationInfo)
    assertNotNull(result.body?.errorMessage)
  }

  @Test
  fun `getPhotoUploadLocation - supports image-png content type`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.VERIFIED)
    val presignedUrl = URI("https://s3.amazonaws.com/bucket/photo.png?presigned=true").toURL()

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(s3UploadService.generatePresignedUploadUrl(eq(offender), eq("image/png"), any<Duration>()))
      .thenReturn(presignedUrl)

    val result = resource.getPhotoUploadLocation(uuid, "image/png")

    assertEquals(HttpStatus.OK, result.statusCode)
    assertNotNull(result.body?.locationInfo)
  }

  @Test
  fun `updateDetails - successful update`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.VERIFIED)
    offender.firstCheckin = LocalDate.now(clock).minusDays(3)

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(offenderRepository.save(offender)).thenReturn(offender)

    val scheduleUpdate = CheckinScheduleUpdateRequest("XYZ0111", clock.today(), CheckinInterval.FOUR_WEEKS)
    val result = resource.updateDetails(uuid, OffenderDetailsUpdateRequest(scheduleUpdate))

    verify(checkinCreationService, times(1)).createCheckin(any(), any(), any())
    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(scheduleUpdate.firstCheckin, result.body?.firstCheckin)
    assertEquals(scheduleUpdate.checkinInterval, result.body?.checkinInterval)
  }

  @Test
  fun `updateDetails - no update`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.VERIFIED)

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(offenderRepository.save(offender)).thenReturn(offender)

    val result = resource.updateDetails(uuid, OffenderDetailsUpdateRequest(checkinSchedule = null))

    verify(checkinCreationService, times(0)).createCheckin(any(), any(), any())
    assertEquals(HttpStatus.NO_CONTENT, result.statusCode)
  }

  @Test
  fun `updateDetails - successful contact preference update`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.VERIFIED).apply {
      contactPreference = ContactPreference.PHONE
    }

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(offenderRepository.save(offender)).thenReturn(offender)

    val preferenceUpdate = ContactPreferenceUpdateRequest("XYZ0111", ContactPreference.EMAIL)

    val result = resource.updateDetails(
      uuid,
      OffenderDetailsUpdateRequest(checkinSchedule = null, contactPreference = preferenceUpdate),
    )

    verify(offenderRepository).save(offender)
    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(ContactPreference.EMAIL, result.body?.contactPreference)
    assertEquals(ContactPreference.EMAIL, offender.contactPreference)
  }

  // ========================================
  // Helper Methods
  // ========================================

  private fun createOffender(uuid: UUID, status: OffenderStatus) = OffenderV2(
    uuid = uuid,
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
