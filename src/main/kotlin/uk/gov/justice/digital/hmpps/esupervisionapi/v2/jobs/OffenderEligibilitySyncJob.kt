package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityCheckOutcome
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityChecker
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityEvaluationEngine
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.eligibilityData
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationService
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.math.min

/**
 * Runs as a one-shot K8s CronJob pod (see docs/HELM-AND-K8s-CRON-JOBS), not @Scheduled —
 * single-run safety comes from the CronJob's concurrencyPolicy: Forbid, dispatched via
 * BatchManager based on BATCH_TYPE=OFFENDER_ELIGIBILITY_SYNC.
 */
@Component
class OffenderEligibilitySyncJob(
  private val clock: Clock,
  private val jobLogRepository: JobLogRepository,
  private val offenderRepository: OffenderRepository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val eligibilityChecker: EligibilityChecker,
  private val offenderDeactivationService: OffenderDeactivationService,
  @param:Value("\${app.scheduling.v2-offender-eligibility-sync.chunk-size:100}") private val chunkSize: Int,
) {
  fun process() {
    val pageSize = min(chunkSize, INdiliusApiClient.MAX_BATCH_SIZE)
    require(pageSize > 0) { "Eligibility sync chunk size must be greater than zero" }

    val now = clock.instant()
    val logEntry = jobLogRepository.saveAndFlush(JobLog(jobType = JOB_TYPE, createdAt = now))
    LOGGER.info("Offender Eligibility Sync Job(id={}) started", logEntry.id)

    var pages = 0
    var processed = 0
    var deactivated = 0
    var failed = 0

    try {
      var lastOffenderId = 0L
      while (true) {
        val offenders = offenderRepository.findAllByStatusAndIdGreaterThanOrderByIdAsc(
          OffenderStatus.VERIFIED,
          lastOffenderId,
          PageRequest.of(0, pageSize),
        )
        if (offenders.isEmpty()) break

        lastOffenderId = offenders.last().id
        pages += 1
        val contactDetailsByCrn = try {
          ndiliusApiClient.getContactDetailsForMultiple(offenders.map { it.crn })
            .associateBy { it.crn }
        } catch (e: Exception) {
          LOGGER.warn("Could not fetch NDelius contact details for eligibility sync page {}", pages, e)
          failed += offenders.size
          continue
        }

        val missingContactDetails = offenders.filter { it.crn !in contactDetailsByCrn }
        if (missingContactDetails.isNotEmpty()) {
          LOGGER.warn(
            "NDelius returned no contact details for {} CRNs in eligibility sync page {}: {}",
            missingContactDetails.size,
            pages,
            missingContactDetails.map { it.crn },
          )
        }

        for (offender in offenders) {
          val contactDetails = contactDetailsByCrn[offender.crn]
          if (contactDetails == null) {
            failed += 1
            continue
          }

          try {
            val result = eligibilityChecker.check(
              offender,
              contactDetails.name,
              prePopulatedCache(contactDetails),
            )
            processed += 1
            if (result.outcome == EligibilityCheckOutcome.INELIGIBLE) {
              val reason = result.message
                ?: "Eligibility rule ${result.triggeredRuleCode ?: "UNKNOWN"} found the offender ineligible for online check-ins."
              offenderDeactivationService.deactivateOffender(
                offender = offender,
                reason = reason,
                contactDetails = contactDetails,
                auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_INELIGIBLE,
                deliusOutcomeCode = result.deliusOutcomeCode,
              )
              deactivated += 1
              LOGGER.info("Stopped online check-ins for CRN {}: {}", offender.crn, reason)
            }
          } catch (e: Exception) {
            failed += 1
            LOGGER.warn("Failed eligibility sync for CRN {} on page {}", offender.crn, pages, e)
          }
        }
      }
    } finally {
      val endTime = clock.instant()
      logEntry.endedAt = endTime
      jobLogRepository.saveAndFlush(logEntry)
      LOGGER.info(
        "Offender Eligibility Sync Job(id={}) completed: processed={}, deactivated={}, failed={}, pages={}, took={}",
        logEntry.id,
        processed,
        deactivated,
        failed,
        pages,
        Duration.between(now, endTime),
      )
    }
  }

  private fun prePopulatedCache(contactDetails: ContactDetails): EligibilityEvaluationEngine.Cache = mapOf(
    "NDELIUS" to CompletableFuture.completedFuture(contactDetails.eligibilityData()),
    "MANUAL" to CompletableFuture.completedFuture(
      mapOf(
        "DEVICE_RESTRICTION" to false,
        "ACCREDITED_PROGRAMME" to true,
        "YOUTH_SENTENCE" to false,
      ),
    ),
  )

  companion object {
    const val JOB_TYPE = "V2_OFFENDER_ELIGIBILITY_SYNC"
    private val LOGGER = LoggerFactory.getLogger(OffenderEligibilitySyncJob::class.java)
  }
}
