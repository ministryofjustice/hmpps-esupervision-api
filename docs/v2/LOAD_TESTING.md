# Load Testing the Check-in Flow

Status: **Draft for review** · Target: **300 check-ins completing within a 60-second window**

This document is the plan for load testing the V2 check-in side of the eSupervision
API. It captures the scope decisions, the constraints discovered in the codebase, and a
tiered execution plan. It does **not** yet include the test harness itself.

## 1. Goal & success criteria

The business question: **can the service cope with ~300 check-ins happening over 60
seconds?** This models a spike — e.g. a cohort of people on probation (POPs) all receive
their reminder at 9am and submit within the same minute.

Proposed pass/fail SLO for a run:

- **300 check-in journeys complete within ≤ 60 seconds.**
- p99 latency on `submit` < 2s; p95 on `liveness/verify` and `upload_location` < 1s.
- Non-4xx error rate < 0.1%.
- Resilience4j circuit breakers (`ndiliusApi`, `awsS3`, `awsRekognition`) **never open**.
- Hikari pool never saturates (no `pending` connections sustained).

> Setup of the 300 offenders is **not** part of the 60s SLA — it is done before the timed
> window. The SLA covers the check-in journey only.

## 2. What a check-in actually costs (request math)

All current check-ins use the **liveness flow** (non-liveness check-ins are
decommissioned). One journey is **~7 server-side API calls**. Crucially, the video and
snapshot **bytes upload directly to S3 via presigned URLs and never pass through the
app** — the app only signs the URLs.

| # | Call | Upstream touched |
|---|------|------------------|
| 1 | `GET  /v2/offender_checkins/{uuid}` | DB |
| 2 | `POST /v2/offender_checkins/{uuid}/identity-verify` | **nDelius** (`POST /case/{crn}/validate-details`) |
| 3 | `POST /v2/offender_checkins/{uuid}/upload_location` | S3 (presign) |
| – | *client PUTs video + snapshots straight to S3* | S3 (not the app) |
| 4 | `POST /v2/offender_checkins/{uuid}/liveness/session` | **Rekognition** (`CreateFaceLivenessSession`) |
| 5 | `GET  /v2/offender_checkins/{uuid}/liveness/credentials` | STS (assume-role) |
| 6 | `POST /v2/offender_checkins/{uuid}/liveness/verify` | **Rekognition** (`GetFaceLivenessSessionResults` + `CompareFaces`), S3 |
| 7 | `POST /v2/offender_checkins/{uuid}/submit` | **nDelius** (`GET /case/{crn}`), DB, SNS/SQS event |

**300 journeys × ~7 calls ≈ 2,100 app requests in 60s ≈ 35 req/s average**, with peaks
higher. Hold this number — it collides with configured limits (Section 4).

All endpoints require role `ROLE_ESUPERVISION__ESUPERVISION_UI` (HMPPS Auth client
credentials, `client_credentials` grant).

## 3. Scope decisions

### 3.1 Rekognition — always stubbed (no choice)

There are two distinct Rekognition operations and they are not equally testable:

| Operation | Endpoints | Needs a live person? | Load-testable? |
|-----------|-----------|----------------------|----------------|
| **Face Liveness** (`CreateFaceLivenessSession` → browser Amplify detector → `GetFaceLivenessSessionResults`) | `liveness/session`, `liveness/credentials`, `liveness/verify` | **Yes** — client-side, real camera + STS creds | **No** |
| **Face match** (`CompareFaces`, setup photo vs snapshot) | inside `liveness/verify` | No — compares two S3 images | Technically yes, with canned images |

Because every current journey is the liveness flow, and liveness genuinely requires a live
camera, **Rekognition is stubbed for the whole flow** via the **`stubrekog`** profile
(`StubRekognitionV2Config.kt`). The stub returns `MATCH` at 99.5% and a fake liveness
session — no AWS calls.

**Implication:** these runs do **not** measure Rekognition throughput, latency, or cost.
If that signal is ever needed it is a separate, optional test that hammers `CompareFaces`
with two static images (no person required) — out of scope here.

### 3.2 nDelius — Tier 1 stub, then Tier 2 real (on **dev**)

The flow hits nDelius synchronously, in the request path, twice: `identity-verify`
(`validate-details`, must return `true` or the check-in is blocked) and `submit`
(`getContactDetails`). Each has a **5s time-limiter** and a circuit breaker that opens at
**50% failure / 80% slow-calls**.

- **Tier 2 must run against Delius _dev_, not preprod.** Preprod Delius holds real CRNs
  that cannot be generated; Delius dev allows generated test CRNs. This is the reason the
  real-integration tier is pinned to dev.
- **Confirmed Hikari risk — `identity-verify`:** `CheckinV2Service.validateIdentity` is
  `@Transactional` (line 120) and calls `ndiliusApiClient.validatePersonalDetails` **inside
  the transaction** (line 142). A slow Delius call (up to the 5s time-limiter) therefore
  holds one of only **10 Hikari connections per pod** for its whole duration. **10
  concurrent slow `identity-verify` calls exhaust a pod's pool** and queue everything else.
  This is the most likely failure mode in Tier 2 and the thing to watch first.
- **`submitCheckin` is lower risk:** it is *not* `@Transactional` at the method level
  (line 224); its `getContactDetails` call (line 229) runs *before* the short persistence
  transaction in `checkinPersistenceService.checkinSubmission`, so the Delius call does not
  hold a connection across the DB write.

> ⚠️ **Stub profile gate:** the nDelius stub bean is `@Profile("local & stubndilius")`
> (`StubServicesConfiguration.kt:20`) — it only activates under the **`local`** profile, so
> it is **not usable in a deployed pod** as-is. Tier 1 in a deployed/prod-shaped instance
> requires either widening that profile guard to a deployable combination, or running a
> dedicated load-test deployment. (See Section 5 prerequisites.)

### 3.3 CRN generation

- **Tier 1 (stub nDelius):** the stub reads its allow-list from
  `src/test/resources/ndelius-responses/default.json` (`{"crns":[...]}`) and
  `GeneratingStubDataProvider` fabricates valid contact details for any listed CRN.
  "Generating 300 CRNs" = writing `X000001`…`X000300` (must match `^[A-Z]\d{6}$`) into that
  file, then running the setup flow to create 300 `VERIFIED` offenders with `CREATED`
  check-ins.
- **Tier 2 (real nDelius on dev):** need 300 CRNs that exist in Delius **dev** with
  matching name/DOB **and** an active event (the `esup-1183` "still on probation" check
  reads `events`). This is a coordination/test-data task with the Delius team — the main
  reason Tier 1 runs first.

## 4. Constraints discovered in the codebase

| Constraint | Value | Impact at 300/60s (~35 rps, ~2,100 req/min) |
|------------|-------|---------------------------------------------|
| nginx ingress `limit-rpm` | preprod/prod **800**, dev/test **500** | 2,100 req/min is **2.6×–4× over** — you'd be testing the throttle, not the app |
| nginx `limit-rps` / burst | preprod 50 / burst 120; dev 20 / burst 50 | dev's 20 rps is below the ~35 rps average — dev ingress will clip hard |
| Hikari pool | **10 connections / pod** | the binding constraint if nDelius calls hold transactions |
| Replicas | dev/test/preprod **2**, prod **4** | test against prod shape, or extrapolate from 2 |
| Heap | **`-Xmx512m`** per pod | tight; watch GC pauses under load |
| Resilience4j time-limiters | nDelius 5s, S3 15s, Rekognition 30s | slow upstreams stall request threads |

**Ingress decision required:** for **Tier 1** (app ceiling) drive load *inside the cluster*
(port-forward / in-namespace runner) to **bypass nginx**. For any end-to-end ingress test,
deliberately **raise `limit-rps`/`limit-rpm`** for the test window — otherwise dev's 20 rps
cap makes 300/60s impossible regardless of app capacity.

## 5. Execution plan

### Tier 1 — App in isolation (do first)

**Profiles:** `stubrekog` + nDelius stubbed. **nDelius and Rekognition both stubbed** so
the only thing under test is the app, DB, S3 presigning, and event publishing.

1. Resolve the `local & stubndilius` profile gate (Section 3.2) for the chosen instance.
2. Seed the 300-CRN stub file; run the setup flow to `VERIFIED` + `CREATED` check-in.
3. Drive load **bypassing the ingress**.
4. Ramp 300 VUs over 60s (constant-arrival-rate), one full journey each.
5. Capture metrics (Section 6). This run answers the headline question.

### Tier 2 — Real nDelius on dev (do second)

**Profiles:** `stubrekog` only; nDelius is **real, against Delius dev**.

1. Obtain 300 generated dev CRNs (with active events) from the Delius test-data team.
2. Seed setup against those CRNs.
3. Either raise dev ingress limits for the window, or run in-namespace.
4. Same workload as Tier 1; **watch circuit-breaker state and Hikari pending connections**
   closely — this run validates the integration, not raw capacity.

### Workload shape

Model the spike: a `constant-arrival-rate` executor injecting 300 journeys across the 60s
window (not 300 truly simultaneous). All 300 offenders pre-seeded before the timed window.

## 6. Metrics to capture

- p50/p95/p99 latency **per endpoint** (especially `submit`, `liveness/verify`, `upload_location`).
- Throughput achieved vs 300 completed in ≤ 60s; non-4xx error rate.
- **App pod:** CPU, heap/GC pause times, Tomcat busy threads.
- **DB:** Hikari active/pending connections, slow queries, lock waits.
- **Resilience4j:** circuit-breaker state transitions (expect CLOSED throughout in Tier 1).
- S3 presign latency; STS assume-role latency.

## 7. Tooling

- `scripts/performance-test.sh` already performs the full setup→checkin→review journey with
  OAuth and synthetic media — reuse it for **seeding** and as a smoke harness. It cannot
  hold a precise arrival rate.
- For the **measured runs**, use **k6** (or Gatling) with `constant-arrival-rate` for exact
  RPS, per-endpoint percentiles, and threshold-based pass/fail. Reuse the bash script's
  OAuth + media-generation logic for seeding.

## 8. Pre-flight checklist

- [ ] Decide instance & whether to scale to prod shape (4 replicas).
- [ ] Resolve ingress handling (bypass for Tier 1; raise limits for ingress tests).
- [ ] Resolve the `local & stubndilius` profile gate for Tier 1.
- [x] ~~Verify whether nDelius calls sit inside `@Transactional`~~ — confirmed:
      `identity-verify` is transactional and holds a connection across the Delius call;
      `submit` is not. Watch the pool during `identity-verify` load in Tier 2.
- [ ] Seed 300 CRNs (stub file for Tier 1; Delius dev test data for Tier 2).
- [ ] Run setup flow to bring all 300 offenders to `VERIFIED` with a `CREATED` check-in.
- [ ] Obtain client credentials with `ROLE_ESUPERVISION__ESUPERVISION_UI`.
- [ ] Confirm S3 target (LocalStack vs real) works for presign + PUT from the runner.

## 9. Open questions

- Prod-shaped (4 replicas) or extrapolate from 2?
- Who owns generating the 300 active-event CRNs in Delius dev for Tier 2?
- Raise dev ingress limits, or run the load generator in-namespace?
- Given the confirmed `identity-verify` Hikari risk: is `identity-verify` always called in
  the real journey, or is it skippable? If always called, consider whether the Delius call
  belongs inside the transaction at all (potential code change, separate from this test).
