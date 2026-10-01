package uk.gov.justice.digital.hmpps.esupervisionapi.jobs.batch

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.OffenderEligibilitySyncJob

/**
 * Entry point for batch-mode pods started by the K8s CronJobs in
 * helm_deploy/hmpps-esupervision-api/values.yaml (batchjobs[]), which set
 * BATCH_ENABLED=true and BATCH_TYPE=<name>. Dispatches to the matching job's
 * process() once the context is up, then exits the pod.
 *
 * As jobs migrate off @Scheduled/@SchedulerLock onto this path, add a constructor
 * param for the job and a matching BatchType branch below.
 */
@Service
@ConditionalOnProperty(name = ["app.batch.enabled"], havingValue = "true")
class BatchManager(
  private val offenderEligibilitySyncJob: OffenderEligibilitySyncJob,
  private val context: ConfigurableApplicationContext,
  @Value("\${app.batch.type}") private val batchType: String,
) {
  @EventListener(ContextRefreshedEvent::class)
  fun run() {
    System.exit(SpringApplication.exit(context, ExitCodeGenerator { dispatch() }))
  }

  /** Runs the job matching BATCH_TYPE and returns the process exit code. Split out from [run] so it can be tested without exiting the JVM. */
  fun dispatch(): Int {
    val type = BatchType.valueOf(batchType)
    LOGGER.info("Batch job {} started", type)

    return try {
      when (type) {
        BatchType.OFFENDER_ELIGIBILITY_SYNC -> offenderEligibilitySyncJob.process()
      }
      LOGGER.info("Batch job {} completed", type)
      0
    } catch (e: Exception) {
      LOGGER.error("Batch job {} failed", type, e)
      1
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(BatchManager::class.java)
  }
}
