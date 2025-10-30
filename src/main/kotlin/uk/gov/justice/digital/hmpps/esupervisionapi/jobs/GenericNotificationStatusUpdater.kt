package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.GenericNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationStatusCollection
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import java.time.Clock
import java.time.Duration

@Service
class GenericNotificationStatusUpdater(
  private val clock: Clock,
  private val notificationService: NotificationService,
  private val genericNotificationRepository: GenericNotificationRepository,
) {

  private val lookbackDays = 7L

  // notification types to ignore (we don't want to process checkin invites here)
  private val ignoredTypes = setOf(NotificationType.OffenderCheckinInvite)

  private data class SyntheticReferencable(private val ref: String) : uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Referencable {
    override fun reference(): String = ref
  }

  /**
   * GOV.UK Notify terminal statuses we stop polling for
   */
  private val terminalStatuses = listOf("delivered", "permanent-failure", "temporary-failure", "technical-failure")

  @Scheduled(cron = "\${app.scheduling.generic-notification-status.cron}")
  @SchedulerLock(name = "GenericNotificationStatusUpdater - update notification status", lockAtLeastFor = "PT5S", lockAtMostFor = "PT10M")
  @Transactional
  fun process() {
    LOG.info("GenericNotificationStatusUpdater starts")
    val now = clock.instant()
    val since = now.minus(Duration.ofDays(lookbackDays))

    try {
      val recent = genericNotificationRepository.findByCreatedAtAfter(since)
      val ignoredTypeNames = ignoredTypes.map { it.name }.toSet()
      val candidates = recent.filter { (it.status == null || !terminalStatuses.contains(it.status!!)) && !ignoredTypeNames.contains(it.messageType) }

      if (candidates.isEmpty()) {
        LOG.info("No generic notifications to process since {}", since)
        LOG.info("GenericNotificationStatusUpdater ends")
        return
      }

      val notificationIdsToUpdate = candidates.map { it.notificationId }.toSet()
      val references = candidates.map { it.reference }.toSet()
      LOG.info("Processing {} references covering {} notifications (since={})", references.size, notificationIdsToUpdate.size, since)

      for (ref in references) {
        try {
          // Page through Notify results (max 250 per page)
          val collected = mutableListOf<NotificationStatusCollection>()
          do {
            val olderThan = collected.lastOrNull()?.previousPageParam
            val batch = notificationService.notificationStatus(SyntheticReferencable(ref), olderThan)
            LOG.info("reference={}, got batch with {} notifications, olderThan={}", ref, batch.notifications.size, olderThan)
            collected.add(batch)
          } while (collected.isNotEmpty() && collected.last().hasNextPage)

          val updates = mutableListOf<NotificationInfo>()
          for (batch in collected) {
            batch.notifications.forEach { if (notificationIdsToUpdate.contains(it.uuid)) updates.add(it) }
          }
          LOG.debug("reference={}, {} records to update", ref, updates.size)

          if (updates.isNotEmpty()) {
            updateNotificationStatus(updates)
          }
        } catch (e: Exception) {
          LOG.warn("Failed to update notification statuses for reference={}: {}", ref, e.message, e)
        }
      }
    } catch (e: Exception) {
      LOG.warn("GenericNotificationStatusUpdater error", e)
    }

    LOG.info("GenericNotificationStatusUpdater ends. took={}", Duration.between(now, clock.instant()))
  }

  fun updateNotificationStatus(notifications: List<NotificationInfo>) {
    val grouped = notifications.groupBy { it.status }
    for (group in grouped) {
      LOG.debug("updating {} notifications with status {}", group.value.size, group.key)
      genericNotificationRepository.updateNotificationStatuses(group.value.map { it.uuid }, group.key)
    }
  }

  companion object {
    private val LOG = LoggerFactory.getLogger(GenericNotificationStatusUpdater::class.java)
  }
}
