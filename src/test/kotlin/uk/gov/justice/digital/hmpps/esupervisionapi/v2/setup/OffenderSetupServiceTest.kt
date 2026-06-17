package uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EligibilityChoice
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

/**
 * Unit tests for OffenderSetupV2Service
 * Covers happy and unhappy paths for setup workflow
 */
class OffenderSetupServiceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-03T10:00:00Z"), ZoneId.of("UTC"))
  private val offenderRepository: OffenderRepository = mock()
  private val offenderSetupRepository: OffenderSetupRepository = mock()
  private val s3UploadService: S3UploadService = mock()
  private val notificationService: NotificationService = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val transactionTemplate: TransactionTemplate = mock()
  private val offenderSetupPersistenceService: OffenderSetupPersistenceService = mock()

  private lateinit var service: OffenderSetupService

  @BeforeEach
  fun setUp() {
    service = OffenderSetupService(
      clock,
      offenderRepository,
      offenderSetupRepository,
      s3UploadService,
      notificationService,
      ndiliusApiClient,
      transactionTemplate,
      Duration.ofDays(3),
      offenderSetupPersistenceService,
    )
  }

  @AfterEach
  fun tearDown() {
    reset(offenderRepository, offenderSetupRepository, s3UploadService, notificationService, ndiliusApiClient, transactionTemplate, offenderSetupPersistenceService)
  }

  @Test
  fun `startOffenderSetup - happy path - creates offender and setup`() {
    // Given
    val crn = "X123456"
    val practitionerId = "PRACT001"
    val firstCheckin = LocalDate.now(clock).plusDays(7)

    val offenderInfo = OffenderInfo(
      setupUuid = UUID.randomUUID(),
      practitionerId = practitionerId,
      crn = crn,
      firstCheckin = firstCheckin,
      checkinInterval = CheckinInterval.WEEKLY,
      contactPreference = ContactPreference.EMAIL,
      eligibilityChoice = EligibilityChoice.SUPPLEMENT_F2F,
      rationale = "it's fine",
    )

    val savedOffender = Offender(
      uuid = UUID.randomUUID(),
      crn = crn,
      practitionerId = practitionerId,
      status = OffenderStatus.INITIAL,
      firstCheckin = firstCheckin,
      checkinInterval = CheckinInterval.WEEKLY.duration,
      createdAt = clock.instant(),
      createdBy = practitionerId,
      updatedAt = clock.instant(),
      contactPreference = ContactPreference.EMAIL,
    )

    val expectedSetup = OffenderSetup(
      uuid = offenderInfo.setupUuid,
      offender = savedOffender,
      practitionerId = practitionerId,
      createdAt = clock.instant(),
      startedAt = offenderInfo.startedAt,
      eligibilityChoice = offenderInfo.eligibilityChoice,
      rationale = offenderInfo.rationale,
    )

    whenever(offenderSetupRepository.save(any())).thenReturn(expectedSetup)

    // When
    val result = service.startOffenderSetup(offenderInfo)

    // Then
    assertNotNull(result)
    assertEquals(offenderInfo.setupUuid, result.uuid)
    assertEquals(practitionerId, result.practitionerId)
    assertEquals(savedOffender.uuid, result.offenderUuid)

    verify(offenderRepository).save(argThat { this.status != OffenderStatus.VERIFIED })
    verify(offenderSetupRepository).save(
      argThat {
        this.uuid == expectedSetup.uuid && rationale == expectedSetup.rationale && eligibilityChoice == expectedSetup.eligibilityChoice
      },
    )
  }

  @Test
  fun `completeOffenderSetup - happy path with photo uploaded - completes setup`() {
    // Given
    val offender = Offender(
      uuid = UUID.randomUUID(),
      crn = "X123456",
      practitionerId = "PRACT001",
      status = OffenderStatus.INITIAL,
      firstCheckin = LocalDate.now(clock).plusDays(1),
      checkinInterval = CheckinInterval.WEEKLY.duration,
      createdAt = clock.instant(),
      createdBy = "PRACT001",
      updatedAt = clock.instant(),
      contactPreference = ContactPreference.EMAIL,
    )

    val setup = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
      startedAt = null,
    )

    whenever(offenderSetupRepository.findByUuid(setup.uuid)).thenReturn(Optional.of(setup))
    whenever(s3UploadService.isSetupPhotoUploaded(setup)).thenReturn(true)
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(null)
    whenever(transactionTemplate.execute<Pair<Offender, Any?>>(any())).thenAnswer {
      val callback = it.getArgument<org.springframework.transaction.support.TransactionCallback<Pair<Offender, Any?>>>(0)
      callback.doInTransaction(null)
    }
    whenever(offenderSetupPersistenceService.completeOffenderSetupAndMaybeCreateCheckin(any(), anyOrNull(), any()))
      .thenReturn(OffenderSetupPersistenceService.Result(checkin = null)) // because no contact info

    // When
    val result = service.completeOffenderSetup(setup.uuid)

    // Then
    assertNotNull(result)
    assertEquals(offender.uuid, result.uuid)
    assertEquals(1, setup.setupCounter)
    verify(s3UploadService).isSetupPhotoUploaded(setup)
    verify(offenderSetupPersistenceService).completeOffenderSetupAndMaybeCreateCheckin(argThat { status == OffenderStatus.VERIFIED }, anyOrNull(), any())
    verify(notificationService).sendSetupCompletedNotifications(any(), isNull(), argThat { setupId == setup.setupId() })
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
    val offender = Offender(
      uuid = UUID.randomUUID(),
      crn = "X123456",
      practitionerId = "PRACT001",
      status = OffenderStatus.INITIAL,
      firstCheckin = LocalDate.now(clock).plusDays(1),
      checkinInterval = CheckinInterval.WEEKLY.duration,
      createdAt = clock.instant(),
      createdBy = "PRACT001",
      updatedAt = clock.instant(),
      contactPreference = ContactPreference.EMAIL,
    )

    val setup = OffenderSetup(
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
  fun `completeOffenderSetup - blocked when contact suspended (in reset) in NDelius`() {
    val offender = makeOffender(clock, LocalDate.now(clock).plusDays(1))
    val setup = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
      startedAt = null,
    )

    whenever(offenderSetupRepository.findByUuid(setup.uuid)).thenReturn(Optional.of(setup))
    whenever(s3UploadService.isSetupPhotoUploaded(setup)).thenReturn(true)
    // Suspended even though an active event is present - suspension wins.
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(
      ContactDetails(crn = offender.crn, name = Name("John", "Doe"), events = listOf(activeEvent), contactSuspended = true),
    )

    assertThrows(BadArgumentException::class.java) {
      service.completeOffenderSetup(setup.uuid)
    }
    verify(offenderRepository, never()).save(any())
    verify(notificationService, never()).sendSetupCompletedNotifications(any(), any(), any())
  }

  @Test
  fun `completeOffenderSetup - blocked when there are no active events in NDelius`() {
    val offender = makeOffender(clock, LocalDate.now(clock).plusDays(1))
    val setup = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
      startedAt = null,
    )

    whenever(offenderSetupRepository.findByUuid(setup.uuid)).thenReturn(Optional.of(setup))
    whenever(s3UploadService.isSetupPhotoUploaded(setup)).thenReturn(true)
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(
      ContactDetails(crn = offender.crn, name = Name("John", "Doe"), events = emptyList()),
    )

    assertThrows(BadArgumentException::class.java) {
      service.completeOffenderSetup(setup.uuid)
    }
    verify(offenderRepository, never()).save(any())
    verify(notificationService, never()).sendSetupCompletedNotifications(any(), any(), any())
  }

  @Test
  fun `completeOffenderSetup - completes when NDelius contact details are unavailable (does not block)`() {
    // A transient NDelius failure must not block onboarding - the eligibility gate only applies
    // when contact details are available. The daily job re-checks eligibility later.
    val offender = makeOffender(clock, LocalDate.now(clock).plusDays(1))
    val setup = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
      startedAt = null,
    )

    whenever(offenderSetupRepository.findByUuid(setup.uuid)).thenReturn(Optional.of(setup))
    whenever(s3UploadService.isSetupPhotoUploaded(setup)).thenReturn(true)
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(null)
    whenever(offenderSetupPersistenceService.completeOffenderSetupAndMaybeCreateCheckin(any(), anyOrNull(), any()))
      .thenReturn(OffenderSetupPersistenceService.Result(checkin = UUID.randomUUID()))

    val result = service.completeOffenderSetup(setup.uuid)

    assertEquals(OffenderStatus.VERIFIED, result.status)
    verify(offenderSetupPersistenceService).completeOffenderSetupAndMaybeCreateCheckin(argThat { status == OffenderStatus.VERIFIED }, anyOrNull(), any())
  }

  @Test
  fun `completeOffenderSetup - completes when offender has an active event`() {
    val offender = makeOffender(clock, LocalDate.now(clock).plusDays(1))
    val setup = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
      startedAt = null,
    )

    whenever(offenderSetupRepository.findByUuid(setup.uuid)).thenReturn(Optional.of(setup))
    whenever(s3UploadService.isSetupPhotoUploaded(setup)).thenReturn(true)
    whenever(ndiliusApiClient.getContactDetails(offender.crn)).thenReturn(
      ContactDetails(crn = offender.crn, name = Name("John", "Doe"), events = listOf(activeEvent)),
    )
    whenever(offenderSetupPersistenceService.completeOffenderSetupAndMaybeCreateCheckin(any(), any(), any()))
      .thenReturn(OffenderSetupPersistenceService.Result(checkin = UUID.randomUUID()))

    val result = service.completeOffenderSetup(setup.uuid)

    assertEquals(OffenderStatus.VERIFIED, result.status)
    verify(offenderSetupPersistenceService).completeOffenderSetupAndMaybeCreateCheckin(argThat { status == OffenderStatus.VERIFIED }, any(), any())
  }

  @Test
  fun `terminateOffenderSetup - happy path - marks offender as inactive`() {
    // Given
    val setupUuid = UUID.randomUUID()
    val offender = Offender(
      uuid = UUID.randomUUID(),
      crn = "X123456",
      practitionerId = "PRACT001",
      status = OffenderStatus.INITIAL,
      firstCheckin = LocalDate.now(clock).plusDays(1),
      checkinInterval = CheckinInterval.WEEKLY.duration,
      createdAt = clock.instant(),
      createdBy = "PRACT001",
      updatedAt = clock.instant(),
      contactPreference = ContactPreference.EMAIL,
    )

    val setup = OffenderSetup(
      uuid = setupUuid,
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
      startedAt = null,
    )

    whenever(offenderSetupRepository.findByUuid(setupUuid)).thenReturn(Optional.of(setup))
    whenever(ndiliusApiClient.getContactDetails(any())).thenReturn(null)
    whenever(transactionTemplate.execute<Offender>(any())).thenAnswer {
      val callback = it.getArgument<org.springframework.transaction.support.TransactionCallback<Offender>>(0)
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

    assertThrows(BadArgumentException::class.java) {
      service.terminateOffenderSetup(setupUuid)
    }
  }

  @Test
  fun `re-starting a setup for a CRN`() {
    val offender = makeOffender(clock, LocalDate.now(clock).plusDays(1))
    val setup = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
      startedAt = null,
    )

    whenever(offenderRepository.findByCrn(offender.crn)).thenReturn(Optional.of(offender))
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(offenderSetupRepository.save(any<OffenderSetup>())).thenAnswer { it.getArgument(0) }

    val first = service.startOffenderSetup(
      OffenderInfo(
        setupUuid = UUID.randomUUID(),
        crn = offender.crn,
        practitionerId = "PRACT001",
        firstCheckin = offender.firstCheckin,
        checkinInterval = CheckinInterval.WEEKLY,
        contactPreference = offender.contactPreference,
        eligibilityChoice = EligibilityChoice.SUPPLEMENT_F2F,
        rationale = "it's fine",
      ),
    )

    val second = service.startOffenderSetup(
      OffenderInfo(
        setupUuid = UUID.randomUUID(),
        crn = offender.crn,
        practitionerId = "PRACT001",
        firstCheckin = offender.firstCheckin,
        checkinInterval = CheckinInterval.WEEKLY,
        contactPreference = offender.contactPreference,
        eligibilityChoice = EligibilityChoice.SUPPLEMENT_F2F,
        rationale = "it's fine",
      ),
    )

    assertTrue(first.uuid != second.uuid)
  }

  @Test
  fun `activateOffenderAndIncrementSetupCounter - INACTIVE offender - increments counter and activates`() {
    val offender = makeOffender(clock, LocalDate.now(clock)).apply {
      status = OffenderStatus.INACTIVE
    }
    val setup = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
    )

    whenever(offenderSetupRepository.findByOffender(offender)).thenReturn(Optional.of(setup))
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(offenderSetupRepository.save(any<OffenderSetup>())).thenAnswer { it.getArgument(0) }

    val (savedOffender, setupId) = service.activateOffenderAndIncrementSetupCounter(offender)

    assertEquals(OffenderStatus.VERIFIED, savedOffender.status)
    assertEquals(2, setup.setupCounter)
    assertNotNull(setupId)
    verify(offenderRepository).save(offender)
    verify(offenderSetupRepository).save(setup)
  }

  @Test
  fun `activateOffenderAndIncrementSetupCounter - already VERIFIED - does not increment counter`() {
    val offender = makeOffender(clock, LocalDate.now(clock)).apply {
      status = OffenderStatus.VERIFIED
    }
    val setup = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
    )

    whenever(offenderSetupRepository.findByOffender(offender)).thenReturn(Optional.of(setup))

    val (returnedOffender, setupId) = service.activateOffenderAndIncrementSetupCounter(offender)

    assertEquals(OffenderStatus.VERIFIED, returnedOffender.status)
    assertEquals(1, setup.setupCounter)
    assertNotNull(setupId)
    verify(offenderRepository, never()).save(any())
    verify(offenderSetupRepository, never()).save(any<OffenderSetup>())
  }

  @Test
  fun `activateOffenderAndIncrementSetupCounter - no setup exists - returns null setupId`() {
    val offender = makeOffender(clock, LocalDate.now(clock)).apply {
      status = OffenderStatus.INACTIVE
    }

    whenever(offenderSetupRepository.findByOffender(offender)).thenReturn(Optional.empty())
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }

    val (savedOffender, setupId) = service.activateOffenderAndIncrementSetupCounter(offender)

    assertEquals(OffenderStatus.VERIFIED, savedOffender.status)
    assertNull(setupId)
    verify(offenderRepository).save(offender)
    verify(offenderSetupRepository, never()).save(any<OffenderSetup>())
  }

  @Test
  fun `activateOffenderAndIncrementSetupCounter - idempotent across lifecycle`() {
    val offender = makeOffender(clock, LocalDate.now(clock)).apply {
      status = OffenderStatus.INACTIVE
    }
    val setup = OffenderSetup(
      uuid = UUID.randomUUID(),
      offender = offender,
      practitionerId = "PRACT001",
      createdAt = clock.instant(),
    )

    whenever(offenderSetupRepository.findByOffender(offender)).thenReturn(Optional.of(setup))
    whenever(offenderRepository.save(any())).thenAnswer { it.getArgument(0) }
    whenever(offenderSetupRepository.save(any<OffenderSetup>())).thenAnswer { it.getArgument(0) }

    // First call: INACTIVE -> VERIFIED, counter 1 -> 2
    val (_, firstSetupId) = service.activateOffenderAndIncrementSetupCounter(offender)
    assertEquals(2, setup.setupCounter)

    // Second call: already VERIFIED, counter stays at 2
    val (_, secondSetupId) = service.activateOffenderAndIncrementSetupCounter(offender)
    assertEquals(2, setup.setupCounter)
    assertEquals(firstSetupId, secondSetupId)
  }
}

private val activeEvent = Event(number = 1L, mainOffence = CodedDescription("X", "An offence"), sentence = null)

fun makeOffender(clock: Clock, firstCheckin: LocalDate) = Offender(
  uuid = UUID.randomUUID(),
  crn = "X123456",
  practitionerId = "PRACT001",
  status = OffenderStatus.INITIAL,
  firstCheckin = firstCheckin,
  checkinInterval = CheckinInterval.WEEKLY.duration,
  createdAt = clock.instant(),
  createdBy = "PRACT001",
  updatedAt = clock.instant(),
  contactPreference = ContactPreference.EMAIL,
)
