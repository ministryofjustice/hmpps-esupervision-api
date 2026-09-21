package uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import io.micrometer.core.annotation.Timed
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import java.time.LocalDate

/**
 * Raised when Supervision Packages could not be asked - an upstream error, a timeout, an open
 * circuit, or an authorisation failure. Deliberately distinct from a `null` result, which means the
 * API answered and does not know the CRN: callers deciding eligibility must not read an outage as
 * "no package, not recalled".
 */
class SupervisionPackagesFetchException(val crn: CRN, message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * The parts of a person's supervision package that eligibility needs, as of now.
 *
 * [supervisionPackage] and [phase] are null together when the person has no active supervision
 * package; [recallStatus] is independent of that and null when there is no active recall NSI.
 */
data class SupervisionPackageDetails(
  /** Package type - `SPA`-`SPG`, or `SPNA` not applicable, `SPNK` not yet known, `SPX` supervised on another sentence. */
  val supervisionPackage: CodedDescription?,
  /** Current phase - e.g. `INIT` early engagement, `STD` standard, `SENT` in custody, `FTHRD` final third, `RRL` post-recall release. */
  val phase: CodedDescription?,
  /**
   * Status of an open "Request for Recall" (`REC`) NSI - how far an undecided recall request has
   * got, e.g. `REC01` "Recall Initiated".
   *
   * Present only while the request awaits a decision. Setting its outcome - recalled, rejected or
   * withdrawn - requires an end date, which terminates the NSI, and Supervision Packages returns
   * only active recall NSIs. So null does not mean "not recalled": a completed recall looks the
   * same as none. Whether the person was actually recalled is in [custody].
   */
  val recallStatus: CodedDescription?,
  /** One entry per custodial sentence in the current supervision period; empty when there are none. */
  val custody: List<CustodyDetails> = emptyList(),
) {
  /**
   * Recalled on any sentence - custody status `C`. Checks every sentence, not only the primary one,
   * as Manage People on Probation does before showing "has been recalled. Their appointments are
   * paused."
   */
  val isRecalled: Boolean get() = custody.any { it.status.code == CustodyDetails.RECALLED }

  /** Unlawfully at large on any sentence - custody location `UATLRG`, as Manage People on Probation checks. */
  val isUnlawfullyAtLarge: Boolean get() = custody.any { it.location?.code == CustodyDetails.UNLAWFULLY_AT_LARGE }
}

/**
 * Where a custodial sentence stands, from Delius's custody, release and recall records.
 *
 * [status] `C` is the direct signal that the person is recalled. [latestRecallDate] agrees with it
 * and dates it: the recall recorded against the most recent release, so set only when they were
 * recalled after that release and have not been released since. A recall followed by a later
 * release is not reported here; the phase shows that as `RRL`.
 */
data class CustodyDetails(
  val eventNumber: String,
  /**
   * Custody status, per Manage People on Probation:
   * `A` Sentenced - In Custody, `D` In Custody, `I` In Custody - IRC, `R` In Custody - RoTL,
   * `C` Recalled, `B` Released - On Licence, `P` Post Sentence Supervision, `AT` Auto Terminated,
   * `T` Terminated.
   */
  val status: CodedDescription,
  /** Where they are held - a prison, or `UATLRG` when unlawfully at large. */
  val location: CodedDescription?,
  /** The most recent release from custody on this sentence; null if never released. */
  val latestReleaseDate: LocalDate?,
  /** The recall that ended the most recent release; null if that release has not been recalled. */
  val latestRecallDate: LocalDate?,
) {
  companion object {
    const val RECALLED = "C"
    const val UNLAWFULLY_AT_LARGE = "UATLRG"
  }
}

interface ISupervisionPackagesApiClient {
  /**
   * Returns null when Supervision Packages does not know the CRN.
   * @throws SupervisionPackagesFetchException when the API could not be asked
   */
  fun getSupervisionPackageDetails(crn: CRN): SupervisionPackageDetails?
}

/**
 * Client for the Supervision Packages API (`hmpps-supervision`), authorised by
 * `PROBATION_API__SUPERVISION_PACKAGE__READ`.
 *
 * Uses `GET /frontend-context/{crn}` - the one call that carries the current phase *and* the case
 * context - as Manage People on Probation does. It answers 404 only for an unknown CRN; a known CRN
 * with no active package is a 200 with `currentPhase: null`, which `/case/{crn}/current-phase` would
 * instead report as a 404.
 *
 * The fallback sits on [Retry], not [CircuitBreaker]. Resilience4j wraps as
 * `Retry(CircuitBreaker(call))`, so a circuit-breaker fallback would turn a 5xx into a result before
 * the retry ever saw it, and nothing would be retried.
 */
@Profile("!stubsupervisionpackages")
@Service
class SupervisionPackagesApiClient(
  private val supervisionPackagesApiWebClient: WebClient,
) : ISupervisionPackagesApiClient {

  @Retry(name = "supervisionPackagesApi", fallbackMethod = "getSupervisionPackageDetailsFallback")
  @CircuitBreaker(name = "supervisionPackagesApi")
  @Timed("supervision-packages.get-frontend-context", extraTags = ["method", "GET", "endpoint", "/frontend-context/{crn}"], description = "Time taken to get supervision package details")
  override fun getSupervisionPackageDetails(crn: CRN): SupervisionPackageDetails? {
    LOGGER.info("Fetching supervision package details for CRN: {}", crn)

    return try {
      supervisionPackagesApiWebClient.get()
        .uri("/frontend-context/{crn}", crn)
        .retrieve()
        .bodyToMono(FrontendContextResponse::class.java)
        .block()
        ?.toDetails()
        ?: throw IllegalStateException("Empty response from Supervision Packages")
    } catch (e: WebClientResponseException.NotFound) {
      LOGGER.info("Supervision Packages does not know CRN: {}", crn)
      null
    }
  }

  private fun getSupervisionPackageDetailsFallback(crn: CRN, e: Exception): SupervisionPackageDetails? {
    LOGGER.error("Supervision Packages unavailable: {}", PiiSanitizer.sanitizeForFallback(e, "getSupervisionPackageDetails, crn=$crn"))
    throw SupervisionPackagesFetchException(crn, "Could not fetch supervision package details for $crn", e)
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(this::class.java)
  }
}

/** Wire shape of `FrontendComponentResponse`, mapping only what we read; the rest is ignored. */
private data class FrontendContextResponse(
  val currentPhase: CurrentPhase?,
  val context: Context?,
) {
  data class CurrentPhase(
    val supervisionPackage: CodedDescription,
    val phase: CodedDescription,
  )

  // The API serialises non_null, so absent collections arrive as missing keys - hence the defaults.
  data class Context(
    val recallStatus: CodedDescription?,
    val sentences: List<Sentence> = emptyList(),
  )

  data class Sentence(
    val eventNumber: String,
    val custody: Custody?,
  )

  data class Custody(
    val status: CodedDescription,
    val location: CodedDescription?,
    val releases: List<Release> = emptyList(),
  )

  data class Release(
    val releaseDate: LocalDate,
    val recallDate: LocalDate?,
  )

  fun toDetails() = SupervisionPackageDetails(
    supervisionPackage = currentPhase?.supervisionPackage,
    phase = currentPhase?.phase,
    recallStatus = context?.recallStatus,
    custody = context?.sentences.orEmpty().mapNotNull { sentence ->
      sentence.custody?.let { custody ->
        val latestRelease = custody.releases.maxByOrNull { it.releaseDate }
        CustodyDetails(
          eventNumber = sentence.eventNumber,
          status = custody.status,
          location = custody.location,
          latestReleaseDate = latestRelease?.releaseDate,
          latestRecallDate = latestRelease?.recallDate,
        )
      }
    },
  )
}
