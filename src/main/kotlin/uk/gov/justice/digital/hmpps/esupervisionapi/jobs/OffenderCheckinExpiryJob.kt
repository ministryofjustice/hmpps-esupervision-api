package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import jakarta.transaction.Transactional
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinRepository
import java.time.Clock
import java.time.Duration
import java.time.Period

@Component
class OffenderCheckinExpiryJob(
  private val clock: Clock,
  private val checkinRepository: OffenderCheckinRepository,
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

    val result = checkinRepository.updateStatusToExpired(cutoff, lowerBound = now.minus(Period.ofDays(28)))

    LOG.info("processing ends. result={}, took={}", result, Duration.between(now, clock.instant()))
  }

  companion object {
    private val LOG = org.slf4j.LoggerFactory.getLogger(OffenderCheckinExpiryJob::class.java)
  }
}
