package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import java.time.Clock
import java.time.Duration

/**
 * One-off cleanup of legacy checkin assets that the live code no longer writes:
 *   - `checkin-{uuid}/1`     - audit-image snapshot removed in ESUP-1770
 *   - `checkin-{uuid}/video` - video upload superseded by face liveness
 *
 * The bucket is versioned, so we permanently remove every version and delete-marker for
 * matching keys (not just the current version). Snapshot 0 (the liveness reference) is
 * untouched.
 *
 * Disabled by default; the bean is only created when
 * `app.scheduling.checkin-legacy-cleanup.enabled=true`. Run with `dry-run=true` first to
 * verify matched counts in the logs, then flip dry-run off, then disable the job entirely.
 */
@Component
@ConditionalOnProperty(name = ["app.scheduling.checkin-legacy-cleanup.enabled"], havingValue = "true")
class CheckinLegacyAssetCleanupJob(
  private val clock: Clock,
  @Qualifier("MOJ") private val s3Client: S3Client,
  private val jobLogRepository: JobLogRepository,
  @Value("\${aws.s3.video-uploads}") private val bucket: String,
  @Value("\${app.scheduling.checkin-legacy-cleanup.dry-run:true}") private val dryRun: Boolean,
) {
  data class Stats(val pages: Int, val scanned: Long, val matched: Long, val deleted: Long, val failed: Long) {
    operator fun plus(other: Stats) = Stats(
      pages = pages + other.pages,
      scanned = scanned + other.scanned,
      matched = matched + other.matched,
      deleted = deleted + other.deleted,
      failed = failed + other.failed,
    )
  }

  @Scheduled(cron = "\${app.scheduling.checkin-legacy-cleanup.cron}")
  @SchedulerLock(
    name = "Checkin Legacy Asset Cleanup Job",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT2H",
  )
  fun process() {
    val started = clock.instant()
    val logEntry = jobLogRepository.saveAndFlush(JobLog(jobType = "V2_CHECKIN_LEGACY_CLEANUP", createdAt = started))
    LOGGER.info(
      "Checkin Legacy Asset Cleanup Job(id={}) started: bucket={}, dryRun={}",
      logEntry.id,
      bucket,
      dryRun,
    )

    var total = Stats(0, 0L, 0L, 0L, 0L)
    try {
      for (filename in LEGACY_FILENAMES) {
        val stats = cleanupCheckinFile(filename)
        LOGGER.info(
          "Checkin Legacy Asset Cleanup Job(id={}) filename={} pages={} scanned={} matched={} deleted={} failed={}",
          logEntry.id,
          filename,
          stats.pages,
          stats.scanned,
          stats.matched,
          stats.deleted,
          stats.failed,
        )
        total += stats
      }
    } catch (e: Exception) {
      LOGGER.error("Checkin Legacy Asset Cleanup Job(id={}) failed mid-run", logEntry.id, e)
    } finally {
      val ended = clock.instant()
      logEntry.endedAt = ended
      jobLogRepository.saveAndFlush(logEntry)
      LOGGER.info(
        "Checkin Legacy Asset Cleanup Job(id={}) completed: pages={}, scanned={}, matched={}, deleted={}, failed={}, dryRun={}, took={}",
        logEntry.id, total.pages, total.scanned, total.matched, total.deleted, total.failed, dryRun,
        Duration.between(started, ended),
      )
    }
  }

  /**
   * Deletes every version and delete-marker of keys shaped `checkin-{uuid}/{filename}` in the
   * configured bucket. The filename is matched literally (anchored, no wildcards).
   */
  internal fun cleanupCheckinFile(filename: String): Stats {
    val keyRegex = Regex("^$KEY_PREFIX$UUID_HEX/${Regex.escape(filename)}$")
    var pages = 0
    var scanned = 0L
    var matched = 0L
    var deleted = 0L
    var failed = 0L
    val batch = ArrayList<ObjectIdentifier>(BATCH_SIZE)

    var keyMarker: String? = null
    var versionIdMarker: String? = null
    var truncated = true

    while (truncated) {
      val res = s3Client.listObjectVersions(
        ListObjectVersionsRequest.builder()
          .bucket(bucket)
          .prefix(KEY_PREFIX)
          .maxKeys(LIST_PAGE_SIZE)
          .keyMarker(keyMarker)
          .versionIdMarker(versionIdMarker)
          .build(),
      )
      pages++

      res.versions().forEach { v ->
        scanned++
        if (keyRegex.matches(v.key())) {
          matched++
          batch += ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build()
          if (batch.size >= BATCH_SIZE) {
            val (ok, ko) = flushBatch(batch)
            deleted += ok
            failed += ko
            batch.clear()
          }
        }
      }
      res.deleteMarkers().forEach { dm ->
        scanned++
        if (keyRegex.matches(dm.key())) {
          matched++
          batch += ObjectIdentifier.builder().key(dm.key()).versionId(dm.versionId()).build()
          if (batch.size >= BATCH_SIZE) {
            val (ok, ko) = flushBatch(batch)
            deleted += ok
            failed += ko
            batch.clear()
          }
        }
      }

      keyMarker = res.nextKeyMarker()
      versionIdMarker = res.nextVersionIdMarker()
      truncated = res.isTruncated == true
    }

    if (batch.isNotEmpty()) {
      val (ok, ko) = flushBatch(batch)
      deleted += ok
      failed += ko
      batch.clear()
    }

    return Stats(pages, scanned, matched, deleted, failed)
  }

  private fun flushBatch(batch: List<ObjectIdentifier>): Pair<Long, Long> {
    val byKey = batch.groupBy { it.key() }
    if (dryRun) {
      byKey.forEach { (key, ids) ->
        LOGGER.info("[dry-run] would delete {} ({} version(s)/marker(s))", key, ids.size)
      }
      LOGGER.info("[dry-run] batch summary: {} unique key(s), {} version(s)/marker(s)", byKey.size, batch.size)
      return 0L to 0L
    }
    byKey.forEach { (key, ids) ->
      LOGGER.info("deleting {} ({} version(s)/marker(s))", key, ids.size)
    }
    val res = s3Client.deleteObjects(
      DeleteObjectsRequest.builder()
        .bucket(bucket)
        .delete(Delete.builder().objects(batch).quiet(true).build())
        .build(),
    )
    val errs = res.errors()
    errs.take(10).forEach { e ->
      LOGGER.warn("delete error: key={} versionId={} code={} msg={}", e.key(), e.versionId(), e.code(), e.message())
    }
    return (batch.size - errs.size).toLong() to errs.size.toLong()
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(CheckinLegacyAssetCleanupJob::class.java)
    private const val KEY_PREFIX = "checkin-"
    private const val UUID_HEX =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private const val BATCH_SIZE = 1000
    private const val LIST_PAGE_SIZE = 1000
    private val LEGACY_FILENAMES = listOf("1", "video")
  }
}
