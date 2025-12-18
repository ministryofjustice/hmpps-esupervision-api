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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
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

  private lateinit var resource: OffenderV2Resource

  @BeforeEach
  fun setUp() {
    resource = OffenderV2Resource(offenderRepository, s3UploadService, clock)
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

  // ========================================
  // Reactivate Tests
  // ========================================

  @Test
  fun `reactivateOffender - happy path - changes INACTIVE to VERIFIED`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(uuid, OffenderStatus.INACTIVE)
    val request = ReactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Back on supervision",
    )

    whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }

    val result = resource.reactivateOffender(uuid, request)

    assertEquals(HttpStatus.OK, result.statusCode)
    assertEquals(OffenderStatus.VERIFIED, result.body?.status)
    assertEquals(uuid, result.body?.uuid)
    verify(offenderRepository).save(any())
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
