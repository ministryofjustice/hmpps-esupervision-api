package uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderInfoV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

/**
 * Unit tests for OffenderSetupV2Service
 * Covers happy and unhappy paths for setup workflow
 */
class OffenderSetupV2ServiceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-03T10:00:00Z"), ZoneId.of("UTC"))
  private val offenderRepository: OffenderV2Repository = mock()
  private val offenderSetupRepository: OffenderSetupV2Repository = mock()
  private val checkinRepository: OffenderCheckinV2Repository = mock()
  private val s3UploadService: S3UploadService = mock()
  private val notificationService: NotificationV2Service = mock()
  private val eventAuditService: EventAuditV2Service = mock()
  private val ndiliusApiClient: NdiliusApiClient = mock()
  private val transactionTemplate: TransactionTemplate = mock()

  private lateinit var service: OffenderSetupV2Service

  @BeforeEach
  fun setUp() {
    service = OffenderSetupV2Service(
      clock,
      offenderRepository,
      offenderSetupRepository,
      checkinRepository,
      s3UploadService,
      notificationService,
      eventAuditService,
      ndiliusApiClient,
      transactionTemplate,
    )
  }

  @Test
  fun `startOffenderSetup - happy path - creates offender and setup`() {
    // Given
    val setupUuid = UUID.randomUUID()
    val crn = "X123456"
    val practitionerId = "PRACT001"
    val firstCheckin = LocalDate.now(clock).plusDays(7)

    val offenderInfo = OffenderInfoV2(
      setupUuid = setupUuid,
      practitionerId = practitionerId,
      crn = crn,
      firstCheckin = firstCheckin,
      checkinInterval = CheckinInterval.WEEKLY,
    )

    val savedOffender = OffenderV2(
      uuid = UUID.randomUUID(),
      crn = crn,
      practitionerId = practitionerId,
      status = OffenderStatus.INITIAL,
      firstCheckin = firstCheckin,
      checkinInterval = CheckinInterval.WEEKLY.duration,
      createdAt = clock.instant(),
      createdBy = practitionerId,
      updatedAt = clock.instant(),
    )

    val savedSetup = OffenderSetupV2(
      uuid = setupUuid,
      offender = savedOffender,
      practitionerId = practitionerId,
      createdAt = clock.instant(),
      startedAt = null,
    )

    whenever(offenderRepository.save(any())).thenReturn(savedOffender)
    whenever(offenderSetupRepository.save(any())).thenReturn(savedSetup)

    // When
    val result = service.startOffenderSetup(offenderInfo)

    // Then
    assertNotNull(result)
    assertEquals(setupUuid, result.uuid)
    assertEquals(practitionerId, result.practitionerId)
    assertEquals(savedOffender.uuid, result.offenderUuid)

    verify(offenderRepository).save(any())
    verify(offenderSetupRepository).save(any())
  }

  @Test
  fun `completeOffenderSetup - happy path with photo uploaded - completes setup`() {
    // Given
    val setupUuid = UUID.randomUUID()
    val offender = OffenderV2(
      uuid = UUID.randomUUID(),
      crn = "X123456",
      practitionerId = "PRACT001",
      status = OffenderStatus.INITIAL,
      firstCheckin = LocalDate.now(clock).plusDays(1),
      checkinInterval = CheckinInterval.WEEKLY.duration,
      createdAt = clock.instant(),
      createdBy = "PRACT001",
      updatedAt = clock.instant(),
    )

    val setup = OffenderSetupV2(
      uuid = setupUuid,
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
      startedAt = null,
    )

    whenever(offenderSetupRepository.findByUuid(setupUuid)).thenReturn(Optional.of(setup))
    whenever(s3UploadService.isSetupPhotoUploaded(setup)).thenReturn(true)
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(null)
    whenever(transactionTemplate.execute<Pair<OffenderV2, Any?>>(any())).thenAnswer {
      val callback = it.getArgument<org.springframework.transaction.support.TransactionCallback<Pair<OffenderV2, Any?>>>(0)
      callback.doInTransaction(null)
    }
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }

    // When
    val result = service.completeOffenderSetup(setupUuid)

    // Then
    assertNotNull(result)
    assertEquals(offender.uuid, result.uuid)
    verify(s3UploadService).isSetupPhotoUploaded(setup)
    verify(offenderRepository).save(any())
  }

  @Test
  fun `completeOffenderSetup - unhappy path - no setup found throws exception`() {
    // Given
    val setupUuid = UUID.randomUUID()
    whenever(offenderSetupRepository.findByUuid(setupUuid)).thenReturn(Optional.empty())

    // When / Then
    assertThrows(BadArgumentException::class.java) {
      service.completeOffenderSetup(setupUuid)
    }
  }

  @Test
  fun `completeOffenderSetup - unhappy path - no photo uploaded throws exception`() {
    // Given
    val setupUuid = UUID.randomUUID()
    val offender = OffenderV2(
      uuid = UUID.randomUUID(),
      crn = "X123456",
      practitionerId = "PRACT001",
      status = OffenderStatus.INITIAL,
      firstCheckin = LocalDate.now(clock).plusDays(1),
      checkinInterval = CheckinInterval.WEEKLY.duration,
      createdAt = clock.instant(),
      createdBy = "PRACT001",
      updatedAt = clock.instant(),
    )

    val setup = OffenderSetupV2(
      uuid = setupUuid,
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
      startedAt = null,
    )

    whenever(offenderSetupRepository.findByUuid(setupUuid)).thenReturn(Optional.of(setup))
    whenever(s3UploadService.isSetupPhotoUploaded(setup)).thenReturn(false)
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(null)

    // When / Then
    assertThrows(InvalidOffenderSetupState::class.java) {
      service.completeOffenderSetup(setupUuid)
    }
  }

  @Test
  fun `terminateOffenderSetup - happy path - marks offender as inactive`() {
    // Given
    val setupUuid = UUID.randomUUID()
    val offender = OffenderV2(
      uuid = UUID.randomUUID(),
      crn = "X123456",
      practitionerId = "PRACT001",
      status = OffenderStatus.INITIAL,
      firstCheckin = LocalDate.now(clock).plusDays(1),
      checkinInterval = CheckinInterval.WEEKLY.duration,
      createdAt = clock.instant(),
      createdBy = "PRACT001",
      updatedAt = clock.instant(),
    )

    val setup = OffenderSetupV2(
      uuid = setupUuid,
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
      startedAt = null,
    )

    whenever(offenderSetupRepository.findByUuid(setupUuid)).thenReturn(Optional.of(setup))
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(null)
    whenever(transactionTemplate.execute<OffenderV2>(any())).thenAnswer {
      val callback = it.getArgument<org.springframework.transaction.support.TransactionCallback<OffenderV2>>(0)
      callback.doInTransaction(null)
    }
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }

    // When
    val result = service.terminateOffenderSetup(setupUuid)

    // Then
    assertNotNull(result)
    assertEquals(OffenderStatus.INACTIVE, result.status)
    verify(offenderSetupRepository).delete(setup)
  }

  @Test
  fun `terminateOffenderSetup - unhappy path - setup not found throws exception`() {
    // Given
    val setupUuid = UUID.randomUUID()
    whenever(offenderSetupRepository.findByUuid(setupUuid)).thenReturn(Optional.empty())

    // When / Then
    assertThrows(BadArgumentException::class.java) {
      service.terminateOffenderSetup(setupUuid)
    }
  }
}
