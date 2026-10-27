# V2 Background Jobs

Scheduled jobs that run automatically to manage checkin lifecycle.

---

## Job Overview

| Job | Purpose | Default Schedule |
|-----|---------|------------------|
| V2CheckinCreationJob | Create checkins for eligible offenders | Daily, configurable |
| V2CheckinExpiryJob | Mark overdue checkins as expired | Daily, configurable |

---

## 1. Checkin Creation Job

Creates checkins for verified offenders based on their configured schedule.

### Flow

```mermaid
flowchart TB
    START([Scheduled Trigger<br/>Cron Expression])

    START --> LOCK{Acquire<br/>ShedLock?}
    LOCK -->|No| SKIP([Skip - Another<br/>Instance Running])
    LOCK -->|Yes| LOG[Create JobLogV2 Entry]

    LOG --> QUERY[Query Eligible Offenders<br/>Status=VERIFIED<br/>Next checkin date = today]

    QUERY --> CHECK{Offenders<br/>Found?}
    CHECK -->|No| COMPLETE

    CHECK -->|Yes| EXISTING[Batch Query Existing<br/>Checkins for Today]
    EXISTING --> FILTER[Filter Out Already<br/>Created]

    FILTER --> BATCH[Process in Batches<br/>of 50 CRNs]

    subgraph "Batch Processing"
        BATCH --> FETCH[Fetch Contact Details<br/>from NDelius]
        FETCH --> PREPARE[Prepare Checkins<br/>for Creation]
        PREPARE --> SAVE[Batch Insert<br/>Checkins]
        SAVE --> NOTIFY[Send Notifications<br/>for Each Checkin]
    end

    NOTIFY --> NEXT{More<br/>Batches?}
    NEXT -->|Yes| BATCH
    NEXT -->|No| COMPLETE

    COMPLETE([Update JobLogV2<br/>endedAt])
```

### Configuration

```yaml
app:
  scheduling:
    v2-checkin-creation:
      cron: "0 0 6 * * *"  # 6 AM daily
```

### Eligibility Criteria

An offender is eligible for checkin creation if:
- `status = VERIFIED`
- `first_checkin IS NOT NULL`
- `checkin_interval IS NOT NULL`
- Next due date falls on today
- No existing checkin for today (any status except CANCELLED)

---

## 2. Checkin Expiry Job

Marks overdue checkins as expired and notifies practitioners.

### Flow

```mermaid
flowchart TB
    START([Scheduled Trigger])

    START --> LOCK{Acquire<br/>ShedLock?}
    LOCK -->|No| SKIP([Skip])
    LOCK -->|Yes| LOG[Create JobLogV2 Entry]

    LOG --> CALC[Calculate Expiry Date<br/>today - gracePeriodDays]

    CALC --> QUERY[Query Checkins<br/>Status=CREATED<br/>dueDate < expiryDate]

    QUERY --> CHECK{Checkins<br/>Found?}
    CHECK -->|No| COMPLETE

    CHECK -->|Yes| UPDATE[Batch Update Status<br/>to EXPIRED]

    UPDATE --> FETCH[Batch Fetch Contact<br/>Details from NDelius]

    FETCH --> LOOP[For Each Checkin]

    subgraph "Notification Loop"
        LOOP --> EVENT[Publish V2_CHECKIN_EXPIRED<br/>Domain Event]
        EVENT --> PNOTIFY[Send Email to<br/>Practitioner]
        PNOTIFY --> AUDIT[Record in<br/>EventAuditV2]
    end

    AUDIT --> NEXT{More<br/>Checkins?}
    NEXT -->|Yes| LOOP
    NEXT -->|No| COMPLETE

    COMPLETE([Update JobLogV2])
```

### Configuration

```yaml
app:
  scheduling:
    v2-checkin-expiry:
      cron: "0 0 7 * * *"  # 7 AM daily
      grace-period-days: 3  # Days after due date before expiry
```

### Expiry Logic

A checkin expires if:
- `status = CREATED` (not submitted)
- `due_date < (today - grace_period_days)`

Example with 3-day grace period:
- Checkin due: January 10
- Checkin window: January 10-12 (3 days to submit)
- Expiry date: January 13 (if still CREATED)

---

## ShedLock

Both jobs use ShedLock to prevent duplicate execution across instances.

```kotlin
@SchedulerLock(
    name = "V2 Checkin Creation Job",
    lockAtLeastFor = "PT5S",    // Hold lock for at least 5 seconds
    lockAtMostFor = "PT30M",    // Release lock after 30 minutes max
)
```

### Lock Table

Uses existing `shedlock` table shared with V1 jobs.

---

## Batch-mode Jobs (K8s CronJob)

Newer jobs run as one-shot K8s CronJob pods instead of `@Scheduled`/ShedLock in the
long-running web pods — see `docs/HELM-AND-K8s-CRON-JOBS.md` for the full rationale.
`OffenderEligibilitySyncJob` (`v2/jobs/OffenderEligibilitySyncJob.kt`) is the first job
on this path; its body is currently a `TODO` and its CronJob is deployed `suspend: true`.

- The pod is started with `BATCH_ENABLED=true` and `BATCH_TYPE=<name>` (from
  `helm_deploy/hmpps-esupervision-api/values.yaml`'s `batchjobs[]`).
- `BatchManager` (`jobs/batch/BatchManager.kt`) listens for `ContextRefreshedEvent`,
  resolves `BATCH_TYPE` against the `BatchType` enum, dispatches to the matching job's
  `process()`, then exits the pod via `SpringApplication.exit`.
- No `@Scheduled`/`@SchedulerLock` — the CronJob's `concurrencyPolicy: Forbid` already
  gives the single-run guarantee ShedLock provides for `@Scheduled` jobs.
- To add a job on this path: give it a `process()` method (same shape as any job above,
  including `JobLogV2` bookkeeping), add a constructor param + `when` branch to
  `BatchManager`, add a `BatchType` entry, and register it in `values.yaml`'s `batchjobs[]`.

### Current Event Backfill

`CurrentEventBackfillJob` is a one-off migration for verified offenders whose `current_event`
is null. It fetches active events from NDelius and assigns the event with the latest known
`expectedEndDate`. If no active sentenced event is returned, apply mode deactivates the offender
through the existing no-active-events flow. Missing contact details or ambiguous end dates are
reported as failures and left unchanged.

The CronJob is suspended and the job defaults to dry-run. First trigger a dry run and review the
logs, which list each proposed event assignment or deactivation:

In the local NDelius stub, CRNs ending in `0` return two active sentences; `X000010` is a sample
where event 2 has the later expected end date.

```bash
# Locally, using the stub profile:
SPRING_PROFILES_ACTIVE=dev,stubrekog,local,stubndilius,stubtier,stubarns \
BATCH_ENABLED=true \
BATCH_TYPE=CURRENT_EVENT_BACKFILL \
CURRENT_EVENT_BACKFILL_DRY_RUN=true \
./gradlew bootRun

# In a deployed namespace:
kubectl create job --from=cronjob/batch-current-event-backfill current-event-backfill-dry-run-$(date +%s) -n <namespace>
kubectl logs -f job/current-event-backfill-dry-run-<timestamp> -n <namespace>
```

After reviewing the output, enable apply mode only on the suspended CronJob and trigger one manual
job. Restore dry-run mode afterward; do not unsuspend the CronJob:

```bash
kubectl set env cronjob/batch-current-event-backfill CURRENT_EVENT_BACKFILL_DRY_RUN=false -n <namespace>
kubectl create job --from=cronjob/batch-current-event-backfill current-event-backfill-apply-$(date +%s) -n <namespace>
kubectl set env cronjob/batch-current-event-backfill CURRENT_EVENT_BACKFILL_DRY_RUN=true -n <namespace>
```

The job only selects rows with a null `current_event`, so rerunning it does not overwrite existing
selections. Any unresolved CRNs make the batch exit non-zero and can be reviewed before retrying.

---

## Job Logging

Each job run creates a record in `job_log_v2`:

```sql
CREATE TABLE job_log_v2 (
    id BIGINT PRIMARY KEY,
    job_type VARCHAR(255) NOT NULL,  -- 'V2_CHECKIN_CREATION' or 'V2_CHECKIN_EXPIRY'
    created_at TIMESTAMP NOT NULL,   -- Job start time
    ended_at TIMESTAMP               -- Job end time (null if still running)
);
```

### Monitoring Queries

```sql
-- Recent job runs
SELECT job_type, created_at, ended_at,
       EXTRACT(EPOCH FROM (ended_at - created_at)) as duration_seconds
FROM job_log_v2
ORDER BY created_at DESC
LIMIT 20;

-- Jobs that didn't complete (potential issues)
SELECT * FROM job_log_v2
WHERE ended_at IS NULL
AND created_at < NOW() - INTERVAL '1 hour';
```

---

## Batch Processing

### NDelius API Batch Size

Contact details are fetched in batches to avoid overloading NDelius:

```kotlin
companion object {
    const val MAX_BATCH_SIZE = 50
}

crns.chunked(NDeliusApiClient.MAX_BATCH_SIZE).forEach { batchCrns ->
    val contactDetailsMap = NDeliusApiClient.getContactDetailsForMultiple(batchCrns)
    // Process batch...
}
```

### Performance Considerations

| Factor | Recommendation |
|--------|----------------|
| Batch size | 50 CRNs per NDelius call |
| Query streaming | Use `Stream<T>` for large result sets |
| Transaction scope | Separate transactions per batch |
| Memory | Process batches sequentially, not all in memory |
