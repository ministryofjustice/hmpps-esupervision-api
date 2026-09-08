package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a scheduled job on demand, for verifying a change in a deployed environment without waiting
 * for its cron.
 *
 * Off by default and enabled per environment via `app.jobs.manual-trigger.enabled`. With the
 * property unset the bean does not exist, so the endpoints 404 rather than 403 - there is nothing
 * to reach in prod even with a valid token.
 *
 * **The job runs on a background thread, not the request thread, and that is the point.** These
 * jobs call NDelius, and how an upstream call is authorised depends on what is bound to the calling
 * thread: `ServletOAuth2AuthorizedClientExchangeFilterFunction` picks up the servlet request and the
 * security context from it, and behaves differently when it finds neither (see
 * `OffenderService.onBehalfOfRequest`, which has to rebind them by hand to keep a call off the
 * request thread authorised). Triggering a job inline would run it under a live request, down a
 * route the 9am scheduler never takes - so an auth fault could pass here and still fail on the real
 * run, which is the class of bug this endpoint most needs to be able to reproduce. Handing the work
 * to an executor puts it in the same context the scheduler uses: no request bound, no security
 * context.
 *
 * Consequently the response only reports that the job *started*. Read the outcome from the job's
 * own logging and its `job_log` row, exactly as for a scheduled run.
 *
 * ShedLock does not apply. `@EnableSchedulerLock` runs in its default `PROXY_SCHEDULER` mode, so
 * `@SchedulerLock` wraps the scheduler rather than the annotated methods, and a direct call bypasses
 * it. A manual run is therefore neither blocked by, nor visible to, a concurrent scheduled run on
 * another pod - hence the in-process guard below, which at least stops one instance stacking runs
 * on itself.
 */
@RestController
@RequestMapping("/v2/jobs")
@ConditionalOnProperty(prefix = "app.jobs.manual-trigger", name = ["enabled"], havingValue = "true")
@Tag(name = "Job Control", description = "Manually trigger a scheduled job (non-production only)")
class JobControlResource(
  checkinCreationJob: ObjectProvider<CheckinCreationJob>,
  checkinReminderJob: ObjectProvider<CheckinReminderJob>,
  checkinExpiryJob: ObjectProvider<CheckinExpiryJob>,
  customQuestionsReminderJob: ObjectProvider<CustomQuestionsReminderJob>,
  checkinImageRetentionJob: ObjectProvider<CheckinImageRetentionJob>,
  checkinNoteResendJob: ObjectProvider<CheckinNoteResendJob>,
  checkinLegacyAssetCleanupJob: ObjectProvider<CheckinLegacyAssetCleanupJob>,
  migrationEventReplayJob: ObjectProvider<MigrationEventReplayJob>,
  monthlyStatsRefreshJob: ObjectProvider<MonthlyStatsRefreshJob>,
) {

  /**
   * Only jobs whose bean is present are registered, so a job disabled in this environment is
   * absent from the listing rather than failing on trigger.
   */
  private val jobs: Map<String, () -> Unit> = buildMap {
    checkinCreationJob.ifAvailable { put("checkin-creation", it::process) }
    checkinReminderJob.ifAvailable { put("checkin-reminder", it::process) }
    checkinExpiryJob.ifAvailable { put("checkin-expiry", it::process) }
    customQuestionsReminderJob.ifAvailable { put("custom-questions-reminder", it::process) }
    checkinImageRetentionJob.ifAvailable { put("checkin-image-retention", it::process) }
    checkinNoteResendJob.ifAvailable { put("checkin-note-resend", it::process) }
    checkinLegacyAssetCleanupJob.ifAvailable { put("checkin-legacy-cleanup", it::process) }
    migrationEventReplayJob.ifAvailable { put("migration-event-replay", it::process) }
    monthlyStatsRefreshJob.ifAvailable { put("monthly-stats-refresh", it::refresh) }
  }

  private val running = ConcurrentHashMap<String, AtomicBoolean>()

  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "manual-job").apply { isDaemon = true }
  }

  @GetMapping
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(summary = "List the jobs that can be triggered in this environment")
  fun list(): Map<String, Any> = mapOf("jobs" to jobs.keys.sorted())

  @PostMapping("/{jobName}/run")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Start a scheduled job now",
    description = "Returns as soon as the job is handed to a background thread. Read the outcome from the job's logs and its job_log row.",
  )
  fun run(@PathVariable jobName: String): ResponseEntity<Map<String, String>> {
    val job = jobs[jobName]
      ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(mapOf("error" to "Unknown job '$jobName'", "available" to jobs.keys.sorted().joinToString(", ")))

    val guard = running.computeIfAbsent(jobName) { AtomicBoolean(false) }
    if (!guard.compareAndSet(false, true)) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(mapOf("job" to jobName, "status" to "ALREADY_RUNNING"))
    }

    LOGGER.info("Manually triggering job {}", jobName)
    try {
      executor.execute {
        try {
          job()
        } catch (e: Exception) {
          // The jobs handle their own failures; this only catches an escape so the guard is released.
          LOGGER.error("Manually triggered job {} failed", jobName, e)
        } finally {
          guard.set(false)
        }
      }
    } catch (e: RejectedExecutionException) {
      // Reachable once [shutdown] has run and a request is still in flight. The guard has to be
      // released here because nothing else will: the task that would have cleared it never runs, and
      // a latched guard would answer ALREADY_RUNNING for a job that is not running.
      guard.set(false)
      LOGGER.warn("Manual trigger of job {} rejected: the executor is shut down", jobName)
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(mapOf("job" to jobName, "status" to "SHUTTING_DOWN"))
    }
    // ACCEPTED, not STARTED: runs are serialised on the single worker, so this may still be queued
    // behind a long job. Saying "started" would have an operator watching for logs that cannot
    // appear yet and concluding the trigger did nothing.
    return ResponseEntity.accepted().body(mapOf("job" to jobName, "status" to "ACCEPTED"))
  }

  /**
   * The thread is a daemon, so a job still running here is abandoned at JVM exit rather than holding
   * shutdown up - and it spends its last moments running against a context that is being torn down.
   * Wait briefly and say so, otherwise the truncated run and its unfinished `job_log` row look like
   * a fault in the job.
   */
  @PreDestroy
  fun shutdown() {
    executor.shutdown()
    if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
      LOGGER.warn("Shutting down with a manually triggered job still running; it will be abandoned")
    }
  }

  companion object {
    private const val SHUTDOWN_GRACE_SECONDS = 2L
    private val LOGGER = LoggerFactory.getLogger(JobControlResource::class.java)
  }
}
