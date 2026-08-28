package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NdiliusBatchFetchException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityCheckOutcome
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityDataUnavailableException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityEvaluationEngine
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityRuleRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.OffenderEligibilityCheckRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.OffenderEligibilityRule
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.eligibilityData
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.jvm.optionals.getOrNull
import kotlin.math.min

private class EligibilitySyncJobMetrics {
  var processed = 0
  var eligible = 0
  var deactivated = 0
  var dataUnavailable = 0
  var errors = 0
  var chunks = 0
}

private fun EligibilitySyncJobMetrics.info(logger: Logger, jobId: Long, duration: Duration) {
  logger.info(
    "Offender Eligibility Sync Job(id={}) completed: processed={}, eligible={}, deactivated={}, dataUnavailable={}, failed={}, chunks={}, took={}",
    jobId,
    this.processed,
    this.eligible,
    this.deactivated,
    this.dataUnavailable,
    this.errors,
    this.chunks,
    duration,
  )
}

/**
 * Re-evaluates every VERIFIED offender against the active [EligibilityEvaluationEngine] rule set
 * and deactivates anyone no longer eligible. Batches NDelius fetches per chunk (both rule sets
 * only use the NDELIUS source today) and feeds them into the engine via its pre-populated cache
 * overload, rather than doing one lazy per-CRN fetch per offender.
 *
 * The last outcome of each (offender, rule set) check is upserted into offender_eligibility_check,
 * making job re-runs idempotent. A source data gap (EligibilityDataUnavailableException) is recorded
 * as DATA_UNAVAILABLE rather than INELIGIBLE, and never triggers a deactivation - same fail-open
 * stance as EligibilityChecker/OffenderSetupService take on this exact exception.
 */
@Component
class OffenderEligibilitySyncJob(
  private val clock: Clock,
  private val jobLogRepository: JobLogRepository,
  private val offenderRepository: OffenderRepository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val eligibilityEvaluationEngine: EligibilityEvaluationEngine,
  private val eligibilityRuleRepository: EligibilityRuleRepository,
  private val offenderEligibilityCheckRepository: OffenderEligibilityCheckRepository,
  private val offenderDeactivationService: OffenderDeactivationService,
  @param:Value("\${app.scheduling.v2-offender-eligibility-sync.chunk-size}") private val chunkSize: Int,
) {
  fun process() {
    val now = clock.instant()
    val logEntry = jobLogRepository.saveAndFlush(JobLog(jobType = JOB_TYPE, createdAt = now))
    LOGGER.info("Offender Eligibility Sync Job(id={}) started", logEntry.id)

    val ruleSet = eligibilityEvaluationEngine.activeRuleSet
    val rulesByCode = eligibilityRuleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(ruleSet).associateBy { it.code }
    val metrics = EligibilitySyncJobMetrics()

    try {
      val finalChunkSize = min(chunkSize, INdiliusApiClient.MAX_BATCH_SIZE)
      var lastOffenderId: Long? = null
      metrics.chunks = 1
      do {
        val chunk = offenderRepository.findVerifiedForEligibilitySync(finalChunkSize, lastOffenderId)
        if (chunk.isEmpty()) {
          break
        }
        lastOffenderId = chunk[chunk.size - 1].id

        LOGGER.info("Processing page {} with {} offenders", metrics.chunks, chunk.size)

        try {
          val contactDetailsMap = getContactDetailsMap(chunk.map { it.crn }, metrics.chunks)
          for (info in chunk) {
            processOffender(info, contactDetailsMap[info.crn], ruleSet, rulesByCode, metrics)
          }
          metrics.processed += chunk.size
          metrics.chunks += 1
        } catch (e: NdiliusBatchFetchException) {
          recordDataUnavailableForChunk(chunk, ruleSet, e.message ?: "NDelius batch fetch failed", metrics)
          metrics.chunks += 1
        }
      } while (chunk.size == finalChunkSize)
    } catch (e: Exception) {
      LOGGER.warn("Offender Eligibility Sync Job(id={}) failed, metrics={}", logEntry.id, metrics, e)
    }

    val endTime = clock.instant()
    logEntry.endedAt = endTime
    jobLogRepository.saveAndFlush(logEntry)

    metrics.info(LOGGER, jobId = logEntry.id, Duration.between(now, endTime))
  }

  private fun processOffender(
    info: OffenderRepository.IOffenderEligibilitySyncInfo,
    contactDetails: ContactDetails?,
    ruleSet: String,
    rulesByCode: Map<String, OffenderEligibilityRule>,
    metrics: EligibilitySyncJobMetrics,
  ) {
    val prePopulatedCache = mapOf(
      "NDELIUS" to
        if (contactDetails != null) {
          CompletableFuture.completedFuture(contactDetails.eligibilityData())
        } else {
          CompletableFuture.failedFuture(RuntimeException("Could not fetch contact details from NDelius for CRN: ${info.crn}"))
        },
    )

    try {
      val result = eligibilityEvaluationEngine.evaluate(info.crn, ruleSet, prePopulatedCache).get()
      val checkedAt = clock.instant()
      when (result.outcome) {
        EligibilityCheckOutcome.ELIGIBLE -> {
          upsertCheck(info.id, ruleSet, result, checkedAt)
          metrics.eligible += 1
        }
        EligibilityCheckOutcome.INELIGIBLE -> {
          upsertCheck(info.id, ruleSet, result, checkedAt)
          deactivateIneligibleOffender(info, contactDetails, result, rulesByCode, metrics)
        }
        EligibilityCheckOutcome.DATA_UNAVAILABLE -> {
          // Not returned by evaluate() itself (see EligibilityCheckOutcome) - handled defensively.
          LOGGER.warn("Unexpected DATA_UNAVAILABLE outcome directly from evaluate() for CRN {}", info.crn)
          upsertCheck(info.id, ruleSet, result, checkedAt)
          metrics.dataUnavailable += 1
        }
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      LOGGER.warn("Eligibility evaluation interrupted for CRN {}", info.crn, e)
      metrics.errors += 1
    } catch (e: ExecutionException) {
      val cause = e.cause
      if (cause is EligibilityDataUnavailableException) {
        offenderEligibilityCheckRepository.upsert(
          offenderId = info.id,
          ruleSet = ruleSet,
          outcome = EligibilityCheckOutcome.DATA_UNAVAILABLE.name,
          message = cause.message,
          triggeredRuleCode = null,
          checkedAt = clock.instant(),
        )
        metrics.dataUnavailable += 1
      } else {
        LOGGER.warn("Failed to evaluate eligibility for CRN {}", info.crn, cause ?: e)
        metrics.errors += 1
      }
    } catch (e: Exception) {
      LOGGER.warn("Failed to evaluate eligibility for CRN {}", info.crn, e)
      metrics.errors += 1
    }
  }

  private fun upsertCheck(offenderId: Long, ruleSet: String, result: EligibilityResult, checkedAt: Instant) = offenderEligibilityCheckRepository.upsert(
    offenderId = offenderId,
    ruleSet = ruleSet,
    outcome = result.outcome.name,
    message = result.message,
    triggeredRuleCode = result.triggeredRuleCode,
    checkedAt = checkedAt,
  )

  private fun deactivateIneligibleOffender(
    info: OffenderRepository.IOffenderEligibilitySyncInfo,
    contactDetails: ContactDetails?,
    result: EligibilityResult,
    rulesByCode: Map<String, OffenderEligibilityRule>,
    metrics: EligibilitySyncJobMetrics,
  ) {
    // Isolate failures per-offender so one bad deactivation doesn't abort the whole run.
    try {
      val auditEventType = rulesByCode[result.triggeredRuleCode]?.auditEventType?.let { OffenderAuditEventType.valueOf(it) }
        ?: OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_INELIGIBLE
      val offender = offenderRepository.findById(info.id).getOrNull() ?: return
      offenderDeactivationService.deactivateOffender(
        offender,
        reason = result.message ?: "Ineligible: rule ${result.triggeredRuleCode ?: "UNKNOWN"}",
        contactDetails = contactDetails,
        auditEventType = auditEventType,
      )
      metrics.deactivated += 1
    } catch (e: Exception) {
      LOGGER.warn("Failed to deactivate CRN {}", info.crn, e)
      metrics.errors += 1
    }
  }

  private fun recordDataUnavailableForChunk(
    chunk: List<OffenderRepository.IOffenderEligibilitySyncInfo>,
    ruleSet: String,
    message: String,
    metrics: EligibilitySyncJobMetrics,
  ) {
    LOGGER.warn("Failed to fetch contact details for {} offenders in chunk {}: {}", chunk.size, metrics.chunks, message)
    val checkedAt = clock.instant()
    for (info in chunk) {
      try {
        offenderEligibilityCheckRepository.upsert(
          offenderId = info.id,
          ruleSet = ruleSet,
          outcome = EligibilityCheckOutcome.DATA_UNAVAILABLE.name,
          message = message,
          triggeredRuleCode = null,
          checkedAt = checkedAt,
        )
      } catch (e: Exception) {
        LOGGER.warn("Failed to record DATA_UNAVAILABLE check for CRN {}", info.crn, e)
      }
    }
    metrics.dataUnavailable += chunk.size
  }

  private fun getContactDetailsMap(crns: List<CRN>, page: Int): Map<String, ContactDetails> {
    val contactDetailsMap = ndiliusApiClient.getContactDetailsForMultiple(crns).associateBy { it.crn }
    val missing = crns.toSet().minus(contactDetailsMap.keys)
    if (missing.isNotEmpty()) {
      LOGGER.info("Contact details not found for {} CRNs in page {}. CRNS: {}", missing.size, page, missing)
    }

    return contactDetailsMap
  }

  companion object {
    const val JOB_TYPE = "V2_OFFENDER_ELIGIBILITY_SYNC"
    private val LOGGER = LoggerFactory.getLogger(OffenderEligibilitySyncJob::class.java)
  }
}
