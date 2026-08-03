package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.time.Clock
import java.time.Duration

/**
 * ESUP-2057: deletes retained check-in images once their retention period expires.
 *
 * MATCH/NO_MATCH images are safe to delete after `standard-retention-days` (default 28).
 * MATCH_WITH_CONCERN images are kept for `concern-retention-days` (default ~6 years) before
 * deletion. Core feature - always enabled.
 *
 * Processed in batches (default 100) to avoid holding long-running transactions: each batch is
 * fetched in its own short transaction, S3 deletes happen outside any transaction, and each
 * successfully-deleted checkin is marked (`imageDeletedAt`) in its own short transaction before
 * the next batch is fetched - so a re-run only ever picks up checkins that are still eligible.
 */
@Component
class CheckinImageRetentionJob(
  private val clock: Clock,
  private val checkinRepository: OffenderCheckinRepository,
  private val s3UploadService: S3UploadService,
  private val jobLogRepository: JobLogRepository,
  private val transactionTemplate: TransactionTemplate,
  private val eventAuditService: EventAuditService,
  @Value("\${app.scheduling.checkin-image-retention.standard-retention-days:28}")
  private val standardRetentionDays: Long,
  @Value("\${app.scheduling.checkin-image-retention.concern-retention-days:2192}")
  private val concernRetentionDays: Long,
  @Value("\${app.scheduling.checkin-image-retention.batch-size:100}")
  private val batchSize: Int,
) {
  data class Stats(var assessed: Int = 0, var deleted: Int = 0, var failed: Int = 0)

  @Scheduled(cron = "\${app.scheduling.checkin-image-retention.cron}")
  @SchedulerLock(
    name = "V2 Checkin Image Retention Job",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT1H",
  )
  fun process() {
    val now = clock.instant()

    val logEntry =
      transactionTemplate.execute {
        jobLogRepository.saveAndFlush(JobLog(jobType = "V2_CHECKIN_IMAGE_RETENTION", createdAt = now))
      }

    val standardCutoff = now.minus(Duration.ofDays(standardRetentionDays))
    val concernCutoff = now.minus(Duration.ofDays(concernRetentionDays))

    LOGGER.info(
      "V2 Checkin Image Retention Job(id={}) started: standardCutoff={}, concernCutoff={}, batchSize={}",
      logEntry.id,
      standardCutoff,
      concernCutoff,
      batchSize,
    )

    var standardStats = Stats()
    var concernStats = Stats()
    try {
      standardStats = runBatchLoop { pageable ->
        val noConcern = setOf(ManualIdVerificationResult.NO_MATCH)
        checkinRepository.findEligibleForImageDeletion(noConcern, standardCutoff, pageable)
      }
      concernStats = runBatchLoop { pageable ->
        val withConcern = setOf(ManualIdVerificationResult.MATCH_WITH_CONCERN)
        checkinRepository.findEligibleForImageDeletion(withConcern, concernCutoff, pageable)
      }
    } catch (e: Exception) {
      LOGGER.error("V2 Checkin Image Retention Job(id={}) failed mid-run", logEntry.id, e)
    }

    val ended = clock.instant()
    transactionTemplate.execute {
      logEntry.endedAt = ended
      jobLogRepository.saveAndFlush(logEntry)
    }

    val totalFailed = standardStats.failed + concernStats.failed
    LOGGER.info(
      "V2 Checkin Image Retention Job(id={}) completed: " +
        "standard(assessed={}, deleted={}, failed={}), concern(assessed={}, deleted={}, failed={}), took={}",
      logEntry.id,
      standardStats.assessed,
      standardStats.deleted,
      standardStats.failed,
      concernStats.assessed,
      concernStats.deleted,
      concernStats.failed,
      Duration.between(now, ended),
    )
    if (totalFailed > 0) {
      LOGGER.error(
        "V2 Checkin Image Retention Job(id={}) had {} failed image deletion(s) - see preceding warnings",
        logEntry.id,
        totalFailed,
      )
    }
  }

  private fun runBatchLoop(fetch: (Pageable) -> List<OffenderCheckin>): Stats {
    val stats = Stats()
    val pageable = PageRequest.of(0, batchSize)
    while (true) {
      val batch = transactionTemplate.execute { fetch(pageable) }
      if (batch.isEmpty()) break
      stats.assessed += batch.size

      for (checkin in batch) {
        try {
          s3UploadService.deleteCheckinSnapshot(checkin.uuid, 0)
          s3UploadService.deleteCheckinVideo(checkin.uuid)
          transactionTemplate.execute {
            checkin.imageDeletedAt = clock.instant()
            checkinRepository.save(checkin)
          }
          eventAuditService.recordCheckinImageDeleted(checkin)
          stats.deleted++
        } catch (e: Exception) {
          LOGGER.warn("Failed to delete image for checkin {}", checkin.uuid, e)
          stats.failed++
        }
      }
    }
    return stats
  }

  companion object {
    private val LOGGER = logger<CheckinImageRetentionJob>()
  }
}
