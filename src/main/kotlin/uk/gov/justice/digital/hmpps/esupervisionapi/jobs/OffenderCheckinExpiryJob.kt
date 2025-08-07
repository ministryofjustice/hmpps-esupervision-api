package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import jakarta.transaction.Transactional
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PractitionerCheckinMissedMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.BulkNotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinRepository
import java.time.Clock
import java.time.Duration
import java.time.Period
import kotlin.streams.asSequence

@Component
class OffenderCheckinExpiryJob(
  private val clock: Clock,
  private val checkinRepository: OffenderCheckinRepository,
  private val jobLogRepository: JobLogRepository,
  private val notificationService: NotificationService,
  @Value("\${app.scheduling.checkin-notification.window:72h}") val checkinWindow: Duration,
) {

  @Scheduled(cron = "\${app.scheduling.offender-checkin-expiry.cron}")
  @SchedulerLock(
    name = "CheckinNotifier - mark checkins as expired",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT10M",
  )
  @Transactional
  fun process() {
    val now = clock.instant()
    val today = now.atZone(clock.zone).toLocalDate()
    val cutoff = today.minus(Period.ofDays(checkinWindow.toDays().toInt()))

    LOG.info("processing starts. checkins below cutoff=$cutoff will be marked as expired.")

    val logEntry = JobLog(JobType.CHECKIN_EXIPIRED_NOTIFICATIONS_JOB, now)
    jobLogRepository.saveAndFlush(logEntry)

    var result: Int? = null
    try {
      val lowerBound = now.minus(Period.ofDays(28))

      val chunkSize = 100
      val context = BulkNotificationContext(logEntry.reference())
      checkinRepository.findAllAboutToExpire(cutoff, lowerBound = lowerBound)
        .asSequence()
        .chunked(chunkSize)
        .forEach { checkins -> notifyPractitioner(checkins, context) }

      result = checkinRepository.updateStatusToExpired(cutoff, lowerBound = lowerBound)
    } catch (e: Exception) {
      LOG.warn("job failure", e)
    }

    val endTime = clock.instant()
    logEntry.endedAt = endTime
    jobLogRepository.saveAndFlush(logEntry)

    LOG.info("processing ends. result={}, took={}", result, Duration.between(now, clock.instant()))
  }

  private fun notifyPractitioner(
    checkins: List<OffenderCheckin>,
    context: BulkNotificationContext,
  ) {
    try {
      for (checkin in checkins) {
        val message = PractitionerCheckinMissedMessage.fromCheckin(checkin)
        notificationService.sendMessage(message, checkin.offender.practitioner, context)
      }
    } catch (e: Exception) {
      LOG.warn("Failed to send practitioner notifications, {}: {}", context, e.message)
    }
  }

  companion object {
    private val LOG = org.slf4j.LoggerFactory.getLogger(OffenderCheckinExpiryJob::class.java)
  }
}
