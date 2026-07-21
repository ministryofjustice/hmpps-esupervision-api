package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * Artificial latency injected into the nDelius / Rekognition **stubs** so that load tests
 * (Tier 1, all upstreams stubbed) represent the thread- and connection-hold time of the real
 * blocking calls. Without it, stubs return instantly, threads recycle immediately, the Hikari
 * pool never fills and the slow-call breaker path is never exercised — overstating capacity.
 *
 * **Disabled by default** (`enabled = false`) so the normal test suite is unaffected; it only
 * does anything when explicitly switched on for a load-test run. Configure under
 * `loadtest.stub-latency.*`, e.g.:
 *
 * ```yaml
 * loadtest:
 *   stub-latency:
 *     enabled: true
 *     ndilius-validate: { mean-ms: 250, jitter-ms: 120 }
 *     ndilius-contact:  { mean-ms: 250, jitter-ms: 120 }
 *     rekog-create-session:  { mean-ms: 200, jitter-ms: 80 }
 *     rekog-session-results: { mean-ms: 400, jitter-ms: 150 }
 *     rekog-compare-faces:   { mean-ms: 350, jitter-ms: 150 }
 * ```
 *
 * Seed the figures from measured reality (Tier 2 for nDelius, prod metrics for Rekognition).
 * See docs/v2/LOAD_TESTING.md §3.4.
 */
@ConfigurationProperties(prefix = "loadtest.stub-latency")
class StubLatencyProperties(
  val enabled: Boolean = false,
  /** nDelius `POST /case/{crn}/validate-details` (identity-verify). */
  val ndiliusValidate: DelaySpec = DelaySpec(),
  /** nDelius `GET /case/{crn}` and batch `POST /cases` (submit and others). */
  val ndiliusContact: DelaySpec = DelaySpec(),
  /** Rekognition `CreateFaceLivenessSession`. */
  val rekogCreateSession: DelaySpec = DelaySpec(),
  /** Rekognition `GetFaceLivenessSessionResults`. */
  val rekogSessionResults: DelaySpec = DelaySpec(),
  /** Rekognition `CompareFaces`. */
  val rekogCompareFaces: DelaySpec = DelaySpec(),
) {
  /** Block the calling thread for a sampled delay — mirrors the real blocking nDelius client. */
  fun sleep(spec: DelaySpec) {
    if (!enabled) return
    val ms = spec.sampleMs()
    if (ms > 0) Thread.sleep(ms)
  }

  /**
   * Executor that fires after a sampled delay **without holding a thread** — mirrors the real
   * async Rekognition client (the request thread is still parked at the eventual `.join()`).
   * Returns a direct executor when disabled or the sampled delay is zero.
   */
  fun delayedExecutor(spec: DelaySpec): Executor {
    if (!enabled) return DIRECT_EXECUTOR
    val ms = spec.sampleMs()
    if (ms <= 0) return DIRECT_EXECUTOR
    return CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS)
  }

  companion object {
    private val DIRECT_EXECUTOR = Executor { it.run() }
  }
}

/** A latency sampled uniformly from `[meanMs - jitterMs, meanMs + jitterMs]`, floored at 0. */
class DelaySpec(
  val meanMs: Long = 0,
  val jitterMs: Long = 0,
) {
  fun sampleMs(): Long {
    if (meanMs <= 0 && jitterMs <= 0) return 0
    if (jitterMs <= 0) return meanMs.coerceAtLeast(0)
    val low = (meanMs - jitterMs).coerceAtLeast(0)
    val high = meanMs + jitterMs
    return if (high <= low) low else ThreadLocalRandom.current().nextLong(low, high + 1)
  }
}
