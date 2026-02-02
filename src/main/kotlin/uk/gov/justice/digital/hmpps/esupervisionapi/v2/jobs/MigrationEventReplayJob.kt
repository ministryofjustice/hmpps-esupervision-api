package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.MigrationEventReplayService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.MigrationControl
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.MigrationControlRepository
import java.time.Clock

@Component
class MigrationEventReplayJob(
  private val clock: Clock,
  private val migrationEventReplayService: MigrationEventReplayService,
  private val jobLogRepository: JobLogV2Repository,
  private val migrationControlRepository: MigrationControlRepository,
  @Value("\${app.scheduling.migration-event-replay.batch-size}") private val batchSize: Int,
) {
  @Scheduled(cron = "\${app.scheduling.migration-event-replay.cron}")
  @SchedulerLock(
    name = "Migration Event Replay Job",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT30M",
  )
  @Transactional
  fun process() {
    val now = clock.instant()
    val logEntry = jobLogRepository.saveAndFlush(JobLogV2(jobType = "MIGRATION_EVENT_REPLAY", createdAt = now))
    LOGGER.info("Migration Event Replay Job(id={}) started", logEntry.id)

    var crns: List<MigrationControl> = emptyList()
    try {
      crns = migrationControlRepository.findAll()
      LOGGER.info("Migration control: crns: ({})", crns.map { it.crn }.joinToString(", "))
    } catch (e: Exception) {
      LOGGER.warn("Migration Event Replay Job(id={}) failed: {}", logEntry.id, e.message)
    }

    val crnToMigrationControl = crns.associateBy { it.crn }

    val processedOffenderEvents = mutableSetOf<String>()
    if (crns.isNotEmpty()) {
      try {
        processedOffenderEvents.addAll(migrationEventReplayService.replayOffenderEvents(crnToMigrationControl, batchSize = batchSize))
        migrationEventReplayService.replayCheckinEvents(crnToMigrationControl, batchSize = batchSize)
      } catch (e: Exception) {
        LOGGER.error("Migration Event Replay Job(id={}) failed: {}", logEntry.id, e.message)
      } finally {
        migrationControlRepository.saveAllAndFlush(crns)
      }
    }

    val endTime = clock.instant()
    logEntry.endedAt = endTime
    jobLogRepository.saveAndFlush(logEntry)
    LOGGER.info("Migration Event Replay Job(id={}) completed", logEntry.id)
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(MigrationEventReplayJob::class.java)
  }
}
