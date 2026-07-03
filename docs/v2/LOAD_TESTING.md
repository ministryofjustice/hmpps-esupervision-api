# Load Testing the Check-in Flow

Status: **Draft for review** · Target: **300 check-ins completing within a 60-second window**

This document is the plan for load testing the V2 check-in side of the eSupervision
API. It captures the scope decisions, the constraints discovered in the codebase, and a
tiered execution plan. It does **not** yet include the test harness itself.

## 1. Goal & success criteria

The business question: **can the service cope with ~300 check-ins happening over 60
seconds?** This models a spike — e.g. a cohort of people on probation (POPs) all receive
their reminder at 9am and start their check-in within the same minute.

**Decided: the 60s window is an _arrival_ window, not a completion window** (see Section 3.6):
300 journeys *begin* within 60s, then each plays out over its real duration with human
think time. "300 journeys complete in 60s" is not physically meaningful once a journey
includes minutes of form-filling and video recording.

> **The 300 figure is an initial estimate, not a fixed requirement.** It should be refined
> as we learn more about realistic cohort sizes and reminder-batching behaviour. When running
> the test, treat 300 as a starting point: if the service clears it comfortably, **keep pushing
> the arrival rate past 300** to find where problems first appear (latency breaching SLO,
> breakers tripping, pool saturation). The goal is to locate the actual breaking point, not
> just to confirm one arbitrary number.

Proposed pass/fail SLO for a run:

- **All 300 journeys that start within the 60s window run to completion** with no server-side
  failures (no 5xx, no dropped requests, no breaker trips), however long the cohort takes to
  drain.
- p99 latency on `submit` < 2s; p95 on `liveness/verify` and `upload_location` < 1s — measured
  on the *server-side call*, excluding client think time.
- Non-4xx error rate < 0.1%.
- Resilience4j circuit breakers (`ndiliusApi`, `awsS3`, `awsRekognition`) **never open**.
- Hikari pool never saturates (no `pending` connections sustained).

> Setup of the 300 offenders is **not** part of the SLA — it is done before the timed window.
> The SLA covers the check-in journey only.

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
higher. Hold this number — it collides with configured limits (Section 4). The harness must
replicate this *real* call sequence at the HTTP level — see Section 3.5 on why the UI mock
liveness component would otherwise under-count.

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

### 3.4 Stub latency injection (required, not optional)

Zero-latency stubs **invalidate Tier 1**. The thing these calls cost under load is the
**time they hold a server thread** (and, for `identity-verify`, a DB connection too).
Confirmed concurrency model:

- nDelius client is **fully blocking** — `.block()` (`NdiliusApiClient.kt:49,90,133`). The
  Tomcat request thread is parked for the whole call.
- Rekognition is async, but the service **`.join()`s the future on the request thread**
  (`CheckinV2Service.kt:512`). Same effect — the request thread is parked for the whole call.

So if the stubs return instantly, threads recycle immediately, the Hikari pool never fills,
and the circuit-breaker slow-call path is never exercised — Tier 1 would **overstate**
capacity. Both stubs must inject representative latency.

**Implemented** via `StubLatencyProperties` (`config/StubLatencyProperties.kt`), configured
under `loadtest.stub-latency.*` and **disabled by default** (so the normal test suite is
unaffected):

- **nDelius stub** (`StubNdiliusApiClient`): `Thread.sleep(delay)` on the calling thread in
  `validatePersonalDetails` / `getContactDetails` / `getContactDetailsForMultiple` — mirrors
  the real blocking call (holds thread **and** the connection inside `validateIdentity`'s
  transaction).
- **Rekognition stub** (`StubRekognitionV2Config`): completes the `CompletableFuture` via a
  *scheduled* `delayedExecutor` (not a pool-thread `sleep`) in `createSession` /
  `getSessionResults` / `verifyCheckinImages` — mirrors the real async client; the request
  thread is still held at `.join()`.
- Each delay is **property-driven** with uniform **jitter** (`mean-ms ± jitter-ms`) so 300
  calls don't complete in lock-step. Example:

  ```yaml
  loadtest:
    stub-latency:
      enabled: true
      ndilius-validate: { mean-ms: 250, jitter-ms: 120 }
      ndilius-contact:  { mean-ms: 250, jitter-ms: 120 }
      rekog-create-session:  { mean-ms: 200, jitter-ms: 80 }
      rekog-session-results: { mean-ms: 400, jitter-ms: 150 }
      rekog-compare-faces:   { mean-ms: 350, jitter-ms: 150 }
  ```

- The figures above are **placeholder assumptions** — replace with **measured reality**: Tier
  2 gives real Delius dev latency; for Rekognition take percentiles from prod metrics (or a
  one-off timing of `CreateFaceLivenessSession` / `GetFaceLivenessSessionResults` /
  `CompareFaces`).

Mind the thresholds the latency interacts with: nDelius slow-call = 3s (breaker), 5s
time-limiter; Rekognition 30s time-limiter. Keep injected means well below these unless you
are deliberately testing the breaker.

### 3.5 Drive load at the API level, not through the UI mock liveness component

The production journey is the 7 server-side calls in Section 2, including `liveness/session`,
`liveness/credentials`, and `liveness/verify`. Two UI facts shape how we generate load:

- The UI app now ships a **mock liveness component**. This is what makes a stubbed-Rekognition
  test viable from a browser at all — the real Amplify `FaceLivenessDetector` needs a live
  camera, so without the mock even a Rekognition-stubbed backend couldn't be driven through
  the UI. Good to have; it unblocks manual/E2E walk-throughs of the stubbed flow.
- **But the mock component does not call the liveness endpoints** (`session` / `credentials`
  / `verify`). A browser/E2E test built on the mock UI would therefore issue **fewer calls
  than production** and under-represent backend load.

**Consequence:** generate load at the **HTTP API level** (k6/script), replicating the *real*
production call sequence regardless of what the mock UI does — do **not** build the load
harness on top of the mock UI. If browser-driven testing is used for any reason, explicitly
add the missing liveness calls so the request count matches production.

### 3.6 Inter-call think time (model the human, not a tight loop)

A real user pauses between the 7 calls — reading screens, filling the survey, recording the
liveness video. Firing all 7 back-to-back is **not** representative: it inflates peak RPS
and per-second thread occupancy beyond anything a real cohort produces, while *under*-stating
how long a journey stays open.

Two consequences to model deliberately:

- **Spread, not a request spike.** With think time the same ~2,100 requests land spread
  across each journey's duration — lower instantaneous RPS, but more journeys in flight at
  once (Little's Law: concurrent journeys ≈ arrival-rate × mean journey duration). The server
  is **stateless between calls** (it holds no thread during think time), so open journeys
  cost little server resource; the per-second request rate is still what drives load.
- **Variance shapes secondary bursts.** If every journey took an identical time, all 300
  `submit` calls would re-cluster into a second spike ~one journey-length after the start
  burst. Real variance smears that out. So sample think time from a realistic *distribution*
  per step, not a fixed delay — too little variance creates artificial sub-bursts, too much
  smooths the load unrealistically.

**Source (decided): production/preprod access logs, grouped by `uuid`.** Every call carries
the check-in `uuid` in its path, so grouping log lines by uuid and diffing timestamps yields
real per-step inter-call delays (access-log analysis is available — best fidelity). Total
journey time also cross-checks against `submitted_at − checkin_started_at` in the DB. Fit a
simple per-step distribution (log-normal is usually a good fit for think time) and sample it
per VU.

**In the harness:** insert sampled `sleep()` between calls in each k6 VU iteration. Optionally
model mid-journey **abandonment** (a fraction drop before `submit`) for extra realism — not
essential for a capacity ceiling test.

> **Note this breaks the naive SLO.** "300 journeys *complete* within 60s" is impossible once
> a journey contains minutes of think time + recording. See Section 1 — the 60s window has to
> be defined as an **arrival** window, not a completion window.

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
2. Add latency injection to the nDelius and Rekognition stubs (Section 3.4).
3. Seed the 300-CRN stub file; run the setup flow to `VERIFIED` + `CREATED` check-in.
4. Drive load at the API level (Section 3.5), **bypassing the ingress**.
5. Ramp 300 VUs over 60s (constant-arrival-rate), one full journey each.
6. Capture metrics (Section 6). This run answers the headline question.

### Tier 2 — Real nDelius on dev (do second)

**Profiles:** `stubrekog` only; nDelius is **real, against Delius dev**.

1. Obtain 300 generated dev CRNs (with active events) from the Delius test-data team.
2. Seed setup against those CRNs.
3. Either raise dev ingress limits for the window, or run in-namespace.
4. Same workload as Tier 1; **watch circuit-breaker state and Hikari pending connections**
   closely — this run validates the integration, not raw capacity.

### Workload shape

Model the spike: a `constant-arrival-rate` executor **starting** 300 journeys across the 60s
window (not 300 truly simultaneous), each then pacing its 7 calls with sampled think time
(Section 3.6). All 300 offenders pre-seeded before the timed window. The cohort drains over
several minutes after the 60s arrival burst — measure peak RPS, thread, and pool usage across
the whole drain, not just the first 60s.

Run two variants:

- **Realistic** — think time sampled from observed data. Answers "can we cope with the real
  300/60s spike." This is the headline run.
- **Compressed / stress** — think time scaled down (or removed) to compress all requests into
  the 60s window. A deliberate worst-case to find the ceiling and headroom margin. Label it
  clearly as pessimistic, not representative.

## 6. Metrics to capture

- p50/p95/p99 latency **per endpoint** (especially `submit`, `liveness/verify`, `upload_location`).
- Throughput achieved vs 300 completed in ≤ 60s; non-4xx error rate.
- **App pod:** CPU, heap/GC pause times, **Tomcat busy threads** (the latency-injected
  stubs are what make this number meaningful — see Section 3.4).
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
- [x] ~~Add property-driven latency (with jitter) to the nDelius + Rekognition stubs~~ — done
      (`StubLatencyProperties`, `loadtest.stub-latency.*`, disabled by default) (§3.4).
- [ ] Obtain representative latency figures per upstream to seed the stubs (§3.4).
- [ ] Confirm the harness scripts the real 7-call sequence, not the mock UI's reduced set (§3.5).
- [ ] Derive per-step think-time distributions from prod/preprod access logs (group by uuid) (§3.6).
- [x] ~~Agree the definition of the "300 in 60s" window~~ — decided: **arrival** window (§1, §9).
- [x] ~~Verify whether nDelius calls sit inside `@Transactional`~~ — confirmed:
      `identity-verify` is transactional and holds a connection across the Delius call;
      `submit` is not. Watch the pool during `identity-verify` load in Tier 2.
- [ ] Seed 300 CRNs (stub file for Tier 1; Delius dev test data for Tier 2).
- [ ] Run setup flow to bring all 300 offenders to `VERIFIED` with a `CREATED` check-in.
- [ ] Obtain client credentials with `ROLE_ESUPERVISION__ESUPERVISION_UI`.
- [ ] Confirm S3 target (LocalStack vs real) works for presign + PUT from the runner.

## 9. Decisions & open questions

**Decided:**
- **"300 in 60s" = arrival window:** 300 journeys *start* within 60s, then drain with realistic
  think time (§3.6, §1). Not 300 submissions in 60s.
- **Think-time source:** production/preprod access logs grouped by check-in `uuid` (§3.6).

**Still open:**
- Prod-shaped (4 replicas) or extrapolate from 2?
- Who owns generating the 300 active-event CRNs in Delius dev for Tier 2?
- Raise dev ingress limits, or run the load generator in-namespace?
- Given the confirmed `identity-verify` Hikari risk: is `identity-verify` always called in
  the real journey, or is it skippable? If always called, consider whether the Delius call
  belongs inside the transaction at all (potential code change, separate from this test).
- What latency figures should the stubs use (§3.4)? Need measured percentiles for Delius
  (`validate-details`, `getContactDetails`) and Rekognition (`CreateFaceLivenessSession`,
  `GetFaceLivenessSessionResults`, `CompareFaces`) — from prod metrics or one-off timings.
