package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationStatusCollection
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinNotificationRepository
import java.time.Clock
import java.time.Duration

@Service
class CheckinNotificationStatusUpdater(
  private val notificationService: NotificationService,
  private val jobLogRepository: JobLogRepository,
  private val checkinNotificationRepository: CheckinNotificationRepository,
  private val clock: Clock,
) {

  /**
   * GOV.UK Notify statuses that we no longer need to update
   */
  val terminalStatuses = listOf("delivered", "permanent-failure", "temporary-failure", "technical-failure")

  @Scheduled(cron = "\${app.scheduling.offender-checkin-notification-status.cron}")
  @SchedulerLock(
    name = "CheckinNotificationStatusUpdater - update notification status",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT10M",
  )
  @Transactional
  fun process() {
    LOG.info("processing starts")

    val now = clock.instant()
    val newerThan = now.atZone(clock.zone).toLocalDate().atStartOfDay().minusDays(5)
    val jobs = jobLogRepository.findByCreatedAtGreaterThanAndJobType(
      newerThan.toInstant(clock.zone.rules.getOffset(now)),
      JobType.CHECKIN_NOTIFICATIONS_JOB,
    )
    LOG.info("Processing {} jobs", jobs.size)

    try {
      for (job in jobs) {
        // GOV.UK Notify returns up to 250 results per call, so we fetch till there are no more results
        val batches = mutableListOf<NotificationStatusCollection>()
        do {
          val batch = notificationService.notificationStatus(job, batches.lastOrNull()?.previousPageParam)
          LOG.info("job reference={}, got batch with {} notifications", job.reference(), batch.notifications.size)
          batches.add(batch)
        } while (batches.isNotEmpty() && batch.hasNextPage)

        // query notifications with terminal failure, filter them out of what
        // we received from the notification service - we don't need to update them anymore
        val terminal = checkinNotificationRepository.findByJobAndStatus(job, terminalStatuses, lowerBound = job.createdAt)
        val terminalIds = terminal.map { it.notificationId }.toSet()
        val notifications = batches
          .flatMap(NotificationStatusCollection::notifications)
          .filter { !terminalIds.contains(it.uuid) }
          .toList()

        LOG.debug("job reference={}, {} records to update", job.reference(), notifications.size)

        // update the remaining notification's status
        try {
          updateNotificationStatus(notifications)
        } catch (e: Exception) {
          LOG.warn("Failed to update notification statuses for job reference=${job.reference()}: ${e.message}", e)
        }
      }
    } catch (e: Exception) {
      LOG.warn("job error", e)
    }

    LOG.info("processing ends. took={}", Duration.between(now, clock.instant()))
  }

  fun updateNotificationStatus(notifications: List<NotificationInfo>) {
    val grouped = notifications.groupBy { it.status }
    for (group in grouped) {
      LOG.debug("updating {} notifications with status {}", group.value.size, group.key)
      checkinNotificationRepository.updateNotificationStatuses(group.value.map { it.uuid }, group.key)
    }
  }

  companion object {
    private val LOG = LoggerFactory.getLogger(CheckinNotificationStatusUpdater::class.java)
  }
}
