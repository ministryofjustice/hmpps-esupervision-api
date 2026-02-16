package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.MigrationEventReplayService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.MigrationCheckinsUuidsRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.MigrationControl
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.MigrationControlRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.MigrationEventsToSend
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import java.time.Clock

@Component
class MigrationEventReplayJob(
  private val clock: Clock,
  private val migrationEventReplayService: MigrationEventReplayService,
  private val jobLogRepository: JobLogV2Repository,
  private val migrationControlRepository: MigrationControlRepository,
  private val migrationCheckinsUuidsRepository: MigrationCheckinsUuidsRepository,
  private val eventAuditService: EventAuditV2Service,
  private val eventAuditRepository: EventAuditV2Repository,
  private val ndiliusApiClient: INdiliusApiClient,
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
      return
    }

    if (crns.isNotEmpty()) {
      val crnToMigrationControl = crns.associateBy { it.crn }

      val processedOffenderEvents = mutableSetOf<String>()
      try {
        processedOffenderEvents.addAll(migrationEventReplayService.replayOffenderEvents(crnToMigrationControl, batchSize = batchSize))
        migrationEventReplayService.replayCheckinEvents(crnToMigrationControl, batchSize = batchSize)
      } catch (e: Exception) {
        LOGGER.error("Migration Event Replay Job(id={}) failed: {}", logEntry.id, e.message)
      } finally {
        migrationControlRepository.saveAllAndFlush(crns)
      }

      try {
        fillAuditEventDetails(crnToMigrationControl.keys)
      } catch (e: Exception) {
        LOGGER.warn("failed fillAuditEventDetails", e)
      }
    }

    val checkinUuids = migrationCheckinsUuidsRepository.findAll()
    LOGGER.info("Migration Event Replay Job(id={}) found {} checkins to replay events for", logEntry.id, checkinUuids.size)
    if (checkinUuids.isNotEmpty()) {
      replaySelectedCheckinEvents(checkinUuids)
    }

    val endTime = clock.instant()
    logEntry.endedAt = endTime
    jobLogRepository.saveAndFlush(logEntry)
    LOGGER.info("Migration Event Replay Job(id={}) completed", logEntry.id)
  }

  fun replaySelectedCheckinEvents(checkins: List<MigrationEventsToSend>) {
    try {
      migrationEventReplayService.replaySelectedCheckinEvents(checkins)
    } catch (e: Exception) {
      LOGGER.warn("Migration Event Replay Job failed", e)
    }
  }

  fun fillAuditEventDetails(crns: Set<String>) {
    LOGGER.info("Fetching contact details for {} CRNs", crns.size)
    // NOTE: we don't have more than 500 in prod, so we take the easy way out
    val contacts = ndiliusApiClient.getContactDetailsForMultiple(crns.toList())
    val contactsByCrn = contacts.associateBy { it.crn }
    val entries = eventAuditRepository.findByCrnOrderByOccurredAt(crns)
    LOGGER.info("fillAuditEventDetails: found {} audit entries, contact details for {} CRNs", entries.size, contactsByCrn.size)
    var numUpdated = 0
    for (entry in entries) {
      val contactDetails = contactsByCrn[entry.crn] ?: continue
      entry.localAdminUnitCode = contactDetails.practitioner?.localAdminUnit?.code
      entry.localAdminUnitDescription = contactDetails.practitioner?.localAdminUnit?.description
      entry.pduCode = contactDetails.practitioner?.probationDeliveryUnit?.code
      entry.pduDescription = contactDetails.practitioner?.probationDeliveryUnit?.description
      entry.providerCode = contactDetails.practitioner?.provider?.code
      entry.providerDescription = contactDetails.practitioner?.provider?.description
      ++numUpdated
    }

    eventAuditRepository.saveAll(entries)
    LOGGER.info("fillAuditEventDetails: updated {} audit entries", numUpdated)
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(MigrationEventReplayJob::class.java)
  }
}
