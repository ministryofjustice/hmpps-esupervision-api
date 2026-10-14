# HELM/K8s CRON JOBS

Key finding: batch-cronjob.yaml (from generic-service ≥3.17) runs a custom command. It starts the same container image as your Deployment, with the same env/envFrom/secrets/security context, but adds BATCH_ENABLED=true and BATCH_TYPE=<job type>.

  This means it's not a Helm-only change — it requires a small, well-defined amount of app code (a "batch mode" entrypoint), even though you don't want that written yet.

  Plan

  1. Chart dependency
  - Bump generic-service in helm_deploy/hmpps-esupervision-api/Chart.yaml from "3.11" to latest (currently 3.17.5) — that's the earliest version where batch-cronjob.yaml is out of "work in progress" status. Re-run helm dependency update.

  2. App-side "batch mode" (new code, not done now)
  - Add batch.enabled / batch.type properties (bound from BATCH_ENABLED/BATCH_TYPE).
  - Add a BatchManager-style @Service (pattern used by hmpps-prisoner-to-nomis-update and hmpps-visit-allocation-api):
    - @ConditionalOnProperty(name = ["batch.enabled"], havingValue = "true")
    - @EventListener on ContextRefreshedEvent → dispatch on batch.type (a BatchType enum) to the existing job's process() method → close the ApplicationContext.
  - No SQS listener suppressor needed — this app only publishes domain events, it doesn't @SqsListener/consume, so that HMPPS README gotcha doesn't apply. DB connections are needed (every job touches Postgres), so no AutoConfiguration exclusion either.
  - Since concurrencyPolicy: Forbid + one-shot pod already gives you the mutual exclusion ShedLock exists for, migrated jobs can drop @SchedulerLock/@Scheduled entirely. Leave shedlock/CheckinNotifierConfiguration in place as long as any job is still Spring-scheduled.
  - Keep each job's JobLog bookkeeping as-is — it's your audit trail, independent of what triggers process().
  - JobControlResource (local-only manual trigger) becomes redundant for migrated jobs — real "run it now" becomes kubectl create job --from=cronjob/... . Can be pruned per-job as each migrates, or left for jobs still on @Scheduled.

  3. values.yaml — add a batchjobs: list

  generic-service:
    cronPrefixName: "batch"   # see naming gotcha below
    batchjobs:
      - name: checkin-creation
        type: V2_CHECKIN_CREATION
        schedule: "0 9 * * *"
      - name: checkin-reminder
        type: V2_CHECKIN_REMINDER
        schedule: "0 9 * * *"
      ...
  Map 1:1 to existing JobLog.jobType strings you already use (V2_CHECKIN_CREATION, V2_CHECKIN_EXPIRY, V2_CHECKIN_REMINDER, V2_PRACTITIONER_CUSTOM_QUESTIONS_REMINDER, MIGRATION_EVENT_REPLAY, CHECKIN_NOTE_RESEND, V2_CHECKIN_LEGACY_CLEANUP) — reuse them as BatchType enum values so JobLog
  history stays consistent across the cutover.

  Naming gotcha to fix up front: the generated CronJob name is trunc 52 of <cronPrefixName or fullname>-<job.name>. Your fullname (hmpps-esupervision-api, 22 chars) + longer job names like v2-practitioner-custom-questions-reminder will collide after truncation. Set cronPrefixName:
  "batch" and use short name: values (checkin-creation, checkin-reminder, checkin-expiry, custom-q-reminder, monthly-stats, migration-replay, note-resend, legacy-cleanup) to stay well under 52 chars.

  4. Per-environment schedules
  You already have per-env cron overrides (APP_SCHEDULING_*_CRON, and disabling via "-") in values-dev/preprod/prod/test.yaml. These become per-env batchjobs[].schedule overrides (Helm merges lists by index, so each env's values file needs its own full batchjobs: list, not a partial
  override) or suspend: true in place of the "-" disable convention. Also note the chart's fixed timezone is Europe/London, which already matches your current cron expressions' implicit assumption (Clock bean is Europe/London), so no time-shifting needed.

  5. Rollout order — pilot first
  Don't convert everything at once. Suggested order, lowest-risk first:
  1. MigrationEventReplayJob or MonthlyStatsRefreshJob — no ShedLock contention concerns, easy to verify, not user-facing-critical.
  2. CheckinLegacyAssetCleanupJob / CheckinNoteResendJob — already disabled-by-default/one-off, good second pass.
  3. CustomQuestionsReminderJob, CheckinReminderJob, CheckinExpiryJob.
  4. CheckinCreationJob last — it's the core, "always enabled" job; migrate only once the pattern is proven.

  For each: deploy with suspend: true, trigger manually via kubectl create job --from=cronjob/..., confirm JobLog rows and logs look identical to the @Scheduled run, then flip suspend: false and remove the old @Scheduled/@SchedulerLock from the Kotlin class in the same PR.

  6. Things to watch
  - concurrencyPolicy: Forbid means a missed/overrunning run is skipped, not queued — same effective behavior as your current ShedLock, just double-check none of your cron windows are tight enough to matter.
  - startingDeadlineSeconds: 600 — if the cluster/control-plane hiccups for >10 min, that run is dropped entirely (no catch-up). Check if any job (e.g. checkin expiry) has same-day correctness assumptions that would break if a run is silently skipped.
  - Pods can be killed mid-run by the Cloud Platform scheduler (rare but real) — jobs with multi-page NDelius batch calls (CheckinCreationJob) should already be idempotent/resumable via your chunking; worth a quick check that a partial run doesn't double-create checkins.
  - Monitoring/alerting tied to @Scheduled job success (if any Prometheus rules key off job execution) will need a new signal — CronJob failure alerts typically come from kube_job_status_failed rather than app metrics.
