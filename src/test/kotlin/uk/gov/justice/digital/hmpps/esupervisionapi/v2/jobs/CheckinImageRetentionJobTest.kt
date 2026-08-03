package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class CheckinImageRetentionJobTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-10T09:00:00Z"), ZoneId.of("UTC"))
  private val checkinRepository: OffenderCheckinRepository = mock()
  private val s3UploadService: S3UploadService = mock()
  private val jobLogRepository: JobLogRepository = mock {
    on { saveAndFlush(any<JobLog>()) } doAnswer { it.arguments[0] as JobLog }
  }
  private val transactionTemplate: TransactionTemplate = mock {
    on { execute<Any?>(any()) } doAnswer {
      (it.getArgument(0) as TransactionCallback<Any?>).doInTransaction(mock())
    }
  }
  private val eventAuditService: EventAuditService = mock()

  private val job = CheckinImageRetentionJob(
    clock,
    checkinRepository,
    s3UploadService,
    jobLogRepository,
    transactionTemplate,
    eventAuditService,
    28L,
    2192L,
    100,
  )

  @Test
  fun `deletes images for eligible standard and concern checkins and records audit`() {
    val standard = checkin(ManualIdVerificationResult.NO_MATCH)
    val concern = checkin(ManualIdVerificationResult.MATCH_WITH_CONCERN)
    whenever(mockStandardImageDeletionQuery()).thenReturn(listOf(standard), emptyList())
    whenever(mockConcernImageDeletionQuery()).thenReturn(listOf(concern), emptyList())

    job.process()

    verify(s3UploadService).deleteCheckinSnapshot(standard.uuid, 0)
    verify(s3UploadService).deleteCheckinVideo(standard.uuid)
    verify(s3UploadService).deleteCheckinSnapshot(concern.uuid, 0)
    verify(s3UploadService).deleteCheckinVideo(concern.uuid)

    verify(eventAuditService).recordCheckinImageDeleted(same(standard))
    verify(eventAuditService).recordCheckinImageDeleted(same(concern))

    val captor = argumentCaptor<OffenderCheckin>()
    verify(checkinRepository, org.mockito.kotlin.times(2)).save(captor.capture())
    captor.allValues.forEach { org.assertj.core.api.Assertions.assertThat(it.imageDeletedAt).isEqualTo(clock.instant()) }
  }

  private fun mockConcernImageDeletionQuery(): List<OffenderCheckin> = checkinRepository.findEligibleForImageDeletion(
    eq(setOf(ManualIdVerificationResult.MATCH_WITH_CONCERN)),
    any(),
    any(),
    any(),
  )

  private fun mockStandardImageDeletionQuery(): List<OffenderCheckin> = checkinRepository.findEligibleForImageDeletion(
    eq(
      setOf(
        ManualIdVerificationResult.NO_MATCH,
      ),
    ),
    any(),
    any(),
    any(),
  )

  @Test
  fun `continues past a failed deletion, leaving that checkin unmarked and uncounted as deleted`() {
    val failing = checkin(ManualIdVerificationResult.MATCH)
    val succeeding = checkin(ManualIdVerificationResult.MATCH)
    whenever(mockStandardImageDeletionQuery()).thenReturn(listOf(failing, succeeding), emptyList())
    whenever(mockConcernImageDeletionQuery()).thenReturn(emptyList())
    whenever(s3UploadService.deleteCheckinSnapshot(eq(failing.uuid), any())).doThrow(RuntimeException("S3 unavailable"))

    job.process()

    verify(eventAuditService, never()).recordCheckinImageDeleted(same(failing))
    verify(eventAuditService).recordCheckinImageDeleted(same(succeeding))
    verify(checkinRepository, never()).save(same(failing))
    verify(checkinRepository).save(same(succeeding))
  }

  @Test
  fun `stops fetching once a batch comes back empty`() {
    whenever(mockStandardImageDeletionQuery()).thenReturn(emptyList())
    whenever(mockConcernImageDeletionQuery()).thenReturn(emptyList())

    job.process()

    val noConcern = setOf(ManualIdVerificationResult.NO_MATCH)
    verify(checkinRepository, org.mockito.kotlin.times(1)).findEligibleForImageDeletion(argThat { crit -> crit == noConcern }, any(), any(), any<Pageable>())
    verify(checkinRepository, org.mockito.kotlin.times(1)).findEligibleForImageDeletion(
      argThat { crit ->
        crit == setOf(
          ManualIdVerificationResult.MATCH_WITH_CONCERN,
        )
      },
      any(),
      any(),
      any<Pageable>(),
    )
    verify(s3UploadService, never()).deleteCheckinSnapshot(any(), any())
  }

  private fun checkin(manualIdCheck: ManualIdVerificationResult) = OffenderCheckin(
    uuid = UUID.randomUUID(),
    offender = offender(),
    status = CheckinStatus.REVIEWED,
    dueDate = LocalDate.now(clock).minusDays(30),
    createdAt = clock.instant().minusSeconds(60 * 60 * 24 * 30),
    createdBy = "SYSTEM",
    submittedAt = clock.instant().minusSeconds(60 * 60 * 24 * 30),
    manualIdCheck = manualIdCheck,
  )

  private fun offender() = Offender(
    uuid = UUID.randomUUID(),
    crn = "X000001",
    practitionerId = "PRACT001",
    status = OffenderStatus.VERIFIED,
    firstCheckin = LocalDate.now(clock).minusDays(60),
    checkinInterval = CheckinInterval.WEEKLY.duration,
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
    updatedAt = clock.instant(),
    contactPreference = ContactPreference.EMAIL,
  )
}
