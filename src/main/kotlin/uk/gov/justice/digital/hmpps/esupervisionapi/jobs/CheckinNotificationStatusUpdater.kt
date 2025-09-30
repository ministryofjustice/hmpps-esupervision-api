package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationStatusCollection
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Referencable
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.SingleNotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import java.time.Clock
import java.time.Duration
import java.time.Instant

private data class QueryParams(val ref: Referencable, val createdAt: Instant)

/**
 * For bulk notifications we use the reference string we passed to the notification service
 * and stored in the JobLog. For one-off notification we use a convention of having a
 * common prefix and including the date in the reference string. This job processes
 * jobs and one-off notifications from a particular time interval (up to a given number of days back).
 */
private data class OneOffNotification(
  override val reference: String,
) : Referencable

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
    val daysBack = 5L
    val newerThan = clock.today().atStartOfDay().minusDays(daysBack)
    val days: List<java.time.LocalDate> =
      (0L..daysBack).map { newerThan.toLocalDate().plusDays(it) }
    val newerThanInstant = newerThan.toInstant(clock.zone.rules.getOffset(now))
    val bulk = jobLogRepository.findByCreatedAtGreaterThanAndJobType(
      newerThanInstant,
      JobType.CHECKIN_NOTIFICATIONS_JOB,
    ).map { QueryParams(it, it.createdAt) }
    val oneOffs = days.map {
      val day = it.atStartOfDay(clock.zone)
      val ctx = SingleNotificationContext.forCheckin(it)
      QueryParams(OneOffNotification(ctx.reference), day.toInstant())
    }
    LOG.info("Processing {} bulk jobs, newer than {}, {} potential one-off jobs", bulk.size, newerThanInstant.toString(), oneOffs.size)

    try {
      for (notificationAttempt in bulk + oneOffs) {
        // GOV.UK Notify returns up to 250 results per call, so we fetch till there are no more results
        val batches = mutableListOf<NotificationStatusCollection>()
        do {
          val olderThan = batches.lastOrNull()?.previousPageParam
          val batch = notificationService.notificationStatus(notificationAttempt.ref, olderThan)
          LOG.info("job reference={}, got batch with {} notifications, older than {}", notificationAttempt.ref.reference, batch.notifications.size, olderThan)
          batches.add(batch)
        } while (batches.isNotEmpty() && batch.hasNextPage)

        // query notifications with terminal failure, filter them out of what
        // we received from the notification service - we don't need to update them anymore
        val terminal = checkinNotificationRepository.findByJobAndStatus(notificationAttempt.ref, terminalStatuses, lowerBound = notificationAttempt.createdAt)
        val terminalIds = terminal.map { it.notificationId }.toSet()
        val notifications = batches
          .flatMap(NotificationStatusCollection::notifications)
          .filter { !terminalIds.contains(it.uuid) }
          .toList()

        LOG.debug("job reference={}, {} records to update", notificationAttempt.ref.reference, notifications.size)

        // update the remaining notification's status
        try {
          updateNotificationStatus(notifications)
        } catch (e: Exception) {
          LOG.warn("Failed to update notification statuses for job reference=${notificationAttempt.ref.reference}: ${e.message}", e)
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
