package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NdiliusBatchFetchException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationFailureException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.BatchCheckinCreationException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.V2CheckinCreationJob.Companion.LOGGER
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlin.math.min
import kotlin.streams.asSequence

private class JobMetrics {
  var processed = 0
  var created = 0
  var errors = 0
  var notifsSent = 0
  var chunks = 0
}

private fun JobMetrics.info(logger: Logger, jobId: Long, duration: Duration) {
  logger.info(
    "Checkin Creation Job(id={}) completed: processed={}, created={}, failed={}, notifications={}, chunks={}, took={}",
    jobId,
    this.processed,
    this.created,
    this.errors,
    this.notifsSent,
    this.chunks,
    duration,
  )
}

/**
 * V2 Checkin Creation Job
 * Creates checkins for verified offenders based on their schedule
 * Core feature - always enabled
 */
@Component
class V2CheckinCreationJob(
  private val clock: Clock,
  private val offenderRepository: OffenderV2Repository,
  private val checkinRepository: OffenderCheckinV2Repository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val checkinCreationService: CheckinCreationService,
  private val notificationService: NotificationV2Service,
  private val jobLogRepository: JobLogV2Repository,
  @PersistenceContext private val entityManager: EntityManager,
  @param:Value("\${app.scheduling.v2-checkin-creation.chunk-size}") private val chunkSize: Int,
) {
  @Scheduled(cron = "\${app.scheduling.v2-checkin-creation.cron}")
  @SchedulerLock(
    name = "V2 Checkin Creation Job",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT30M",
  )
  @Transactional
  fun process() {
    val now = clock.instant()
    val today = LocalDate.now(clock)
    val logEntry = jobLogRepository.saveAndFlush(JobLogV2(jobType = "V2_CHECKIN_CREATION", createdAt = now))
    LOGGER.info("Checkin Creation Job(id={}) started: creating checkins for date={}", logEntry.id, today)
    val metrics = JobMetrics()

    try {
      val lowerBound = today
      val upperBound = today.plusDays(1)
      val finalChunkSize = min(chunkSize, INdiliusApiClient.MAX_BATCH_SIZE)

      offenderRepository.findEligibleForCheckinCreation(lowerBound, upperBound).use { stream ->
        stream.asSequence()
          .chunked(finalChunkSize)
          .forEach { chunk ->
            try {
              metrics.chunks += 1
              LOGGER.info("Processing chunk {} with {} offenders", metrics.chunks, chunk.size)

              val offendersByCrn = chunk.associateBy { it.crn }
              val contactDetailsMap = getContactDetailsMap(offendersByCrn.keys.toList(), metrics.chunks)
              val missing = offendersByCrn.keys.minus(contactDetailsMap.keys)
              if (missing.isNotEmpty()) {
                LOGGER.info("Contact details not found for {} CRNs in chunk {}. CRNS: {}", missing.size, metrics.chunks, missing)
              }

              val checkinsToCreate = mutableListOf<Pair<OffenderCheckinV2, ContactDetails>>()
              for (crn in contactDetailsMap.keys) {
                val checkin = checkinCreationService.prepareCheckinForOffender(offendersByCrn[crn]!!, today)
                checkinsToCreate.add(Pair(checkin, contactDetailsMap[crn]!!))
              }
              metrics.processed += contactDetailsMap.size

              val savedCheckins = checkinCreationService.batchCreateCheckins(checkinsToCreate.map { it.first })
              metrics.created += savedCheckins.size

              for ((checkin, contactDetails) in checkinsToCreate) {
                notificationService.sendCheckinCreatedNotifications(checkin, contactDetails)
                metrics.notifsSent += 1
              }
            } catch (e: NdiliusBatchFetchException) {
              metrics.errors += e.crns.size // already logged elsewhere
            } catch (e: BatchCheckinCreationException) {
              LOGGER.warn("Failed to create {} checkins for chunk {}", e.checkins.size, metrics.chunks, e)
              metrics.errors += e.checkins.size
            } catch (e: NotificationFailureException) {
              LOGGER.warn("Failed to send notifications for chunk {}: {}", metrics.chunks, e.message) // stack logged elsewhere
            }

            entityManager.flush()
            entityManager.clear()
          }
      }
    } catch (e: Exception) {
      LOGGER.warn("Checkin Creation Job(id={}) failed, metrics={}", logEntry.id, metrics, e)
    }

    val endTime = clock.instant()
    logEntry.endedAt = endTime
    jobLogRepository.saveAndFlush(logEntry)

    metrics.info(LOGGER, jobId = logEntry.id, Duration.between(now, endTime))
  }

  private fun getContactDetailsMap(
    crns: List<String>,
    numChunks: Int,
  ): Map<String, ContactDetails> {
    val contactDetailsMap = try {
      ndiliusApiClient.getContactDetailsForMultiple(crns).associateBy { it.crn }
    } catch (e: NdiliusBatchFetchException) {
      LOGGER.warn("Failed to fetch contact details for chunk {}", numChunks, e)
      throw e
    }
    return contactDetailsMap
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(V2CheckinCreationJob::class.java)
  }
}
