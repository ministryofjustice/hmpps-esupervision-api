package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.GeneratingStubDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.PresignedUpload
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup.OffenderSetupService
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

class OffenderResourceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-10T10:00:00Z"), ZoneId.of("UTC"))
  private val offenderRepository: OffenderRepository = mock()
  private val s3UploadService: S3UploadService = mock()
  private val checkinCreationService: CheckinCreationService = mock()
  private val eventAuditService: EventAuditService = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val notificationService: NotificationService = mock()
  private val checkinRepository: OffenderCheckinRepository = mock()
  private val offenderSetupService: OffenderSetupService = mock()
  private val offenderDeactivationService: OffenderDeactivationService = mock()
  private val appConfig: AppConfig = mock()

  private lateinit var resource: OffenderResource

  private val anEvent = Event(number = 1L, mainOffence = CodedDescription("X", "An offence"), sentence = null)

  @BeforeEach
  fun setUp() {
    reset(
      offenderRepository, s3UploadService, checkinCreationService, eventAuditService, ndiliusApiClient,
      notificationService, checkinRepository, offenderSetupService, offenderDeactivationService, appConfig,
    )
    resource = OffenderResource(
      offenderRepository,
      s3UploadService,
      clock,
      checkinCreationService,
      eventAuditService,
      ndiliusApiClient,
      notificationService,
      checkinRepository,
      offenderSetupService,
      offenderDeactivationService,
      appConfig,
    )
  }

  // ========================================
  // Deactivate Tests
  // ========================================

  @Test
  fun `deactivateOffender - happy path - delegates to deactivation service and returns INACTIVE`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.VERIFIED)
    val request = DeactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "No longer on supervision",
    )
    val contactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      crn = offender.crn,
      mobile = "07700900123",
      name = uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("John", "Doe"),
    )
    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)
    whenever(offenderDeactivationService.deactivateOffender(any(), any(), any(), any(), any())).thenAnswer {
      val o = it.getArgument<Offender>(0)
      o.status = OffenderStatus.INACTIVE
      o
    }

    val result = resource.deactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(OffenderStatus.INACTIVE, result.body?.status)
    assertEquals(uuid, result.body?.uuid)
    verify(offenderDeactivationService).deactivateOffender(
      eq(offender),
      eq("No longer on supervision"),
      eq(contactDetails),
      eq(false),
      eq(OffenderAuditEventType.OFFENDER_DEACTIVATED),
    )
  }

  @Test
  fun `deactivateOffender - passes sensitive flag through to deactivation service`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.VERIFIED)
    val request = DeactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Safety concerns disclosed",
      sensitive = true,
    )
    val contactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      crn = offender.crn,
      mobile = "07700900123",
      name = uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("John", "Doe"),
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)
    whenever(offenderDeactivationService.deactivateOffender(any(), any(), any(), any(), any())).thenAnswer {
      it.getArgument<Offender>(0)
    }

    resource.deactivateOffender(uuid, request)

    verify(offenderDeactivationService).deactivateOffender(
      eq(offender),
      eq("Safety concerns disclosed"),
      eq(contactDetails),
      eq(true),
      eq(OffenderAuditEventType.OFFENDER_DEACTIVATED),
    )
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
    val contactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      crn = offender.crn,
      mobile = "07700900123",
      name = uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("John", "Doe"),
    )

    val presignedUrl = URI("https://s3.amazonaws.com/bucket/photo.jpg?presigned=true").toURL()
    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)
    whenever(offenderDeactivationService.deactivateOffender(any(), any(), any(), any(), any())).thenAnswer {
      val o = it.getArgument<Offender>(0)
      o.status = OffenderStatus.INACTIVE
      o
    }
    // mock s3
    whenever(s3UploadService.getOffenderPhoto(any())).thenReturn(presignedUrl)

    val result = resource.deactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(OffenderStatus.INACTIVE, result.body?.status)
    assertEquals(uuid, result.body?.uuid)
    assertEquals("https://s3.amazonaws.com/bucket/photo.jpg?presigned=true", result.body?.photoUrl)
  }

  // ========================================
  // Reactivate Tests
  // ========================================

  @Test
  fun `reactivateOffender - happy path - changes INACTIVE to VERIFIED, creates check in for today and sends notification`() {
    val uuid = UUID.randomUUID()
    val today = LocalDate.now(clock)
    val offender = createOffender(uuid, OffenderStatus.INACTIVE).apply {
      firstCheckin = today
      contactPreference = ContactPreference.PHONE
    }
    val request = ReactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Back on supervision",
    )
    val checkin = mock<uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin>()

    val contactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      crn = offender.crn,
      name = uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("John", "Doe"),
      mobile = "07700900123",
      events = listOf(anEvent),
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(offenderSetupService.activateOffenderAndIncrementSetupCounter(any())).thenAnswer {
      val o = it.getArgument<Offender>(0)
      o.status = OffenderStatus.VERIFIED
      Pair(o, null)
    }
    whenever(checkinCreationService.createCheckin(any(), any(), any())).thenReturn(checkin)
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)

    val result = resource.reactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(OffenderStatus.VERIFIED, result.body?.status)

    verify(offenderSetupService).activateOffenderAndIncrementSetupCounter(offender)
    verify(notificationService).sendReactivationCompletedNotifications(eq(offender), eq(contactDetails), isNull())
  }

  @Test
  fun `reactivateOffender - with schedule update - applies new schedule but does not create check in`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INACTIVE).apply {
      contactPreference = ContactPreference.PHONE
    }
    val futureDate = LocalDate.now(clock).plusDays(7)
    val request = ReactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Restart with new schedule",
      checkinSchedule = CheckinScheduleUpdateRequest(
        requestedBy = "PRACT001",
        firstCheckin = futureDate,
        checkinInterval = CheckinInterval.TWO_WEEKS,
      ),
    )

    val contactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      crn = offender.crn,
      mobile = "07700900123",
      name = uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("John", "Doe"),
      events = listOf(anEvent),
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)
    whenever(offenderSetupService.activateOffenderAndIncrementSetupCounter(any())).thenAnswer {
      val o = it.getArgument<Offender>(0)
      o.status = OffenderStatus.VERIFIED
      Pair(o, null)
    }

    val result = resource.reactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(futureDate, result.body?.firstCheckin)
    verify(offenderSetupService).activateOffenderAndIncrementSetupCounter(offender)
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
    val offender = createOffender(uuid, OffenderStatus.INACTIVE).apply {
      contactPreference = ContactPreference.PHONE
    }
    val request = ReactivateOffenderRequest(requestedBy = "PRACT001", reason = "Restarting supervision")

    val contactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      crn = offender.crn,
      name = uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("John", "Doe"),
      mobile = "07700900123",
      events = listOf(anEvent),
    )

    val presignedUrl = URI("https://s3.amazonaws.com/bucket/photo.jpg?presigned=true").toURL()
    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)
    whenever(offenderSetupService.activateOffenderAndIncrementSetupCounter(any())).thenAnswer {
      val o = it.getArgument<Offender>(0)
      o.status = OffenderStatus.VERIFIED
      Pair(o, null)
    }
    whenever(s3UploadService.getOffenderPhoto(any())).thenReturn(presignedUrl)

    val result = resource.reactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals("https://s3.amazonaws.com/bucket/photo.jpg?presigned=true", result.body?.photoUrl)
  }

  @Test
  fun `reactivateOffender - missing mobile number for PHONE preference - throws 400`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INACTIVE).apply {
      contactPreference = ContactPreference.PHONE
    }
    val request = ReactivateOffenderRequest(requestedBy = "PRACT001", reason = "Restarting")
    val contactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      offender.crn,
      uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("John", "Doe"),
      mobile = null,
      events = listOf(anEvent),
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)

    val exception = assertThrows(ResponseStatusException::class.java) {
      resource.reactivateOffender(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    assertTrue(exception.reason?.contains("does not have a mobile number in NDelius") == true)
  }

  @Test
  fun `reactivateOffender - missing email for EMAIL preference update - throws 400`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INACTIVE).apply {
    }
    val request = ReactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Restarting",
      contactPreference = ContactPreferenceUpdateRequest("PRACT001", ContactPreference.EMAIL),
    )

    val contactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      offender.crn,
      uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("Jane", "Smith"),
      email = "",
      events = listOf(anEvent),
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)

    val exception = assertThrows(ResponseStatusException::class.java) {
      resource.reactivateOffender(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    assertTrue(exception.reason?.contains("does not have an email address in NDelius") == true)
  }

  @Test
  fun `reactivateOffender - already completed today - first check in set to TODAY - creates a NEW fresh check in`() {
    val uuid = UUID.randomUUID()
    val today = LocalDate.now(clock)
    val offender = createOffender(uuid, OffenderStatus.INACTIVE).apply {
      firstCheckin = today
      contactPreference = ContactPreference.PHONE
    }
    val request = ReactivateOffenderRequest(requestedBy = "PRACT001", reason = "Re-engagement")

    val myContactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      offender.crn,
      uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("Jane", "Smith"),
      mobile = "07700900123",
      events = listOf(anEvent),
    )

    val completedCheckin = mock<uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin>()
    whenever(completedCheckin.status).thenReturn(CheckinStatus.SUBMITTED)
    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(myContactDetails)
    whenever(checkinRepository.findByOffenderAndDueDate(offender, today)).thenReturn(Optional.of(completedCheckin))
    whenever(offenderSetupService.activateOffenderAndIncrementSetupCounter(any())).thenAnswer {
      val o = it.getArgument<Offender>(0)
      o.status = OffenderStatus.VERIFIED
      Pair(o, null)
    }

    resource.reactivateOffender(uuid, request)

    verify(checkinCreationService).createCheckin(eq(uuid), eq(today), eq("PRACT001"))
  }

  @Test
  fun `reactivateOffender - cancelled check in exists - first check in set to TODAY - creates a NEW fresh check in`() {
    val uuid = UUID.randomUUID()
    val today = LocalDate.now(clock)
    val offender = createOffender(uuid, OffenderStatus.INACTIVE).apply {
      firstCheckin = today
      contactPreference = ContactPreference.PHONE
    }
    val request = ReactivateOffenderRequest(requestedBy = "PRACT001", reason = "Reactivating")

    val myContactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      offender.crn,
      uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("Jane", "Smith"),
      mobile = "07700900123",
      events = listOf(anEvent),
    )

    val cancelledCheckin = mock<uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin>()
    whenever(cancelledCheckin.status).thenReturn(CheckinStatus.CANCELLED)

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(myContactDetails)
    whenever(checkinRepository.findByOffenderAndDueDate(offender, today)).thenReturn(Optional.of(cancelledCheckin))
    whenever(offenderSetupService.activateOffenderAndIncrementSetupCounter(any())).thenAnswer {
      val o = it.getArgument<Offender>(0)
      o.status = OffenderStatus.VERIFIED
      Pair(o, null)
    }

    resource.reactivateOffender(uuid, request)

    verify(checkinCreationService).createCheckin(eq(uuid), eq(today), eq("PRACT001"))
  }

  @Test
  fun `reactivateOffender - first check in set to future date - does NOT create check in today`() {
    val uuid = UUID.randomUUID()
    val tomorrow = LocalDate.now(clock).plusDays(1)
    val offender = createOffender(uuid, OffenderStatus.INACTIVE).apply {
      firstCheckin = tomorrow
      contactPreference = ContactPreference.PHONE
    }
    val request = ReactivateOffenderRequest(requestedBy = "PRACT001", reason = "Reactivating with future start")

    val myContactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      offender.crn,
      uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("Jane", "Smith"),
      mobile = "07700900123",
      events = listOf(anEvent),
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(myContactDetails)
    whenever(offenderSetupService.activateOffenderAndIncrementSetupCounter(any())).thenAnswer {
      val o = it.getArgument<Offender>(0)
      o.status = OffenderStatus.VERIFIED
      Pair(o, null)
    }

    val result = resource.reactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(OffenderStatus.VERIFIED, result.body?.status)

    verify(checkinCreationService, times(0)).createCheckin(any(), any(), any())
    verify(notificationService).sendReactivationCompletedNotifications(eq(offender), eq(myContactDetails), isNull())
  }

  @Test
  fun `reactivateOffender - blocked when contact suspended (in reset) in NDelius`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INACTIVE).apply {
      contactPreference = ContactPreference.PHONE
    }
    val request = ReactivateOffenderRequest(requestedBy = "PRACT001", reason = "Reactivating")
    val contactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      offender.crn,
      uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("John", "Doe"),
      mobile = "07700900123",
      events = listOf(anEvent),
      contactSuspended = true,
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)

    val exception = assertThrows(ResponseStatusException::class.java) {
      resource.reactivateOffender(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    verify(offenderSetupService, times(0)).activateOffenderAndIncrementSetupCounter(any())
    verify(checkinCreationService, times(0)).createCheckin(any(), any(), any())
  }

  @Test
  fun `reactivateOffender - blocked when there are no active events in NDelius`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INACTIVE).apply {
      contactPreference = ContactPreference.PHONE
    }
    val request = ReactivateOffenderRequest(requestedBy = "PRACT001", reason = "Reactivating")
    val contactDetails = uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails(
      offender.crn,
      uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name("John", "Doe"),
      mobile = "07700900123",
      events = emptyList(),
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(contactDetails)

    val exception = assertThrows(ResponseStatusException::class.java) {
      resource.reactivateOffender(uuid, request)
    }

    assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    verify(offenderSetupService, times(0)).activateOffenderAndIncrementSetupCounter(any())
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
    whenever(s3UploadService.generatePresignedUpload(eq(offender), eq("image/jpeg"), any<Duration>(), isNull()))
      .thenReturn(PresignedUpload(presignedUrl, emptyMap()))

    val result = resource.getPhotoUploadLocation(uuid, "image/jpeg", null)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertNotNull(result.body?.locationInfo)
    assertEquals(presignedUrl.toString(), result.body?.locationInfo?.url.toString())
    assertEquals("image/jpeg", result.body?.locationInfo?.contentType)
  }

  @Test
  fun `getPhotoUploadLocation - offender not found - returns 404`() {
    val uuid = UUID.randomUUID()

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.empty())

    val result = resource.getPhotoUploadLocation(uuid, "image/jpeg", null)

    assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
  }

  @Test
  fun `getPhotoUploadLocation - offender INACTIVE - returns 400 with error message`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INACTIVE)

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))

    val result = resource.getPhotoUploadLocation(uuid, "image/jpeg", null)

    assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
    assertNull(result.body?.locationInfo)
    assertNotNull(result.body?.errorMessage)
  }

  @Test
  fun `getPhotoUploadLocation - offender INITIAL - returns 400 with error message`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INITIAL)

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))

    val result = resource.getPhotoUploadLocation(uuid, "image/jpeg", null)

    assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
    assertNull(result.body?.locationInfo)
    assertNotNull(result.body?.errorMessage)
  }

  @Test
  fun `getPhotoUploadLocation - unsupported content type - returns 400 with error message`() {
    val uuid = UUID.randomUUID()

    val result = resource.getPhotoUploadLocation(uuid, "application/pdf", null)

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
    whenever(s3UploadService.generatePresignedUpload(eq(offender), eq("image/png"), any<Duration>(), isNull()))
      .thenReturn(PresignedUpload(presignedUrl, emptyMap()))

    val result = resource.getPhotoUploadLocation(uuid, "image/png", null)

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

  @Test
  fun `getOffenderByCrn - success`() {
    val offender = createOffender(UUID.randomUUID(), OffenderStatus.VERIFIED)
    whenever(offenderRepository.findByCrn(offender.crn)).thenReturn(Optional.of(offender))
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenAnswer { GeneratingStubDataProvider().provideCase(crn = offender.crn) }

    val resultWithoutDetails = resource.getOffenderByCrn(offender.crn, includePersonalDetails = false)
    assertNotNull(resultWithoutDetails.body)
    assertNull(resultWithoutDetails.body?.details)

    val resultWithDetails = resource.getOffenderByCrn(offender.crn, includePersonalDetails = true)
    assertNotNull(resultWithDetails.body?.details)
  }

  // ========================================
  // Helper Methods
  // ========================================

  private fun createOffender(uuid: UUID, status: OffenderStatus) = Offender(
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
