package uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import java.time.Duration

/**
 * Publishes recorded check-in events to NDelius (see [CheckinDeliusEventService.publishPending]).
 * Runs often and is usually a no-op, so it doesn't write a job log entry.
 */
@Component
@ConditionalOnProperty(name = ["app.scheduling.checkin-delius-event-relay.enabled"], havingValue = "true")
class CheckinDeliusEventRelayJob(
  private val checkinDeliusEventService: CheckinDeliusEventService,
  @Value("\${app.scheduling.checkin-delius-event-relay.batch-size}") private val batchSize: Int,
  @Value("\${app.scheduling.checkin-delius-event-relay.min-gap}") private val minGap: Duration,
) {
  @Scheduled(fixedDelayString = "\${app.scheduling.checkin-delius-event-relay.fixed-delay}")
  @SchedulerLock(
    name = "Checkin Delius Event Relay Job",
    lockAtLeastFor = "PT1S",
    lockAtMostFor = "PT5M",
  )
  fun process() {
    try {
      val published = checkinDeliusEventService.publishPending(batchSize, minGap)
      if (published > 0) {
        LOGGER.info("Checkin Delius Event Relay Job published {} events", published)
      }
    } catch (e: Exception) {
      LOGGER.error("Checkin Delius Event Relay Job failed", e)
    }
  }

  companion object {
    private val LOGGER = logger<CheckinDeliusEventRelayJob>()
  }
}
