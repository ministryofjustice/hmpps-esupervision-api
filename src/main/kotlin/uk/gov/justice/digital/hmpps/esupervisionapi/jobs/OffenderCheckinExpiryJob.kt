package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import jakarta.transaction.Transactional
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.events.CheckinAdditionalInformation
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DOMAIN_EVENT_VERSION
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.events.HmppsDomainEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.events.PersonReference
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PractitionerCheckinMissedMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.BulkNotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerRepository
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
  private val practitionerRepository: PractitionerRepository,
  private val eventPublisher: DomainEventPublisher,
  private val appConfig: AppConfig,
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
        val practitioner = practitionerRepository.expectById(checkin.offender.practitioner)
        val message = PractitionerCheckinMissedMessage.fromCheckin(checkin, practitioner)
        notificationService.sendMessage(message, practitioner, context)

        if (checkin.offender.crn != null) {
          eventPublisher.publish(checkinExpiredEvent(checkin))
        } else {
          LOG.warn("Missing CRN for offender={}", checkin.offender.uuid)
        }
      }
    } catch (e: Exception) {
      LOG.warn("Failed to send practitioner notifications, {}: {}", context, e.message)
    }
  }

  private fun checkinExpiredEvent(checkin: OffenderCheckin): HmppsDomainEvent {
    assert(checkin.offender.crn != null)
    val checkinUrl = appConfig.checkinDashboardUrl(checkin.uuid).toURL()
    val event = HmppsDomainEvent(
      DomainEventType.CHECKIN_EXPIRED.type,
      version = DOMAIN_EVENT_VERSION,
      detailUrl = checkinUrl.toString(),
      occurredAt = clock.instant().atZone(clock.zone),
      description = DomainEventType.CHECKIN_EXPIRED.description,
      additionalInformation = CheckinAdditionalInformation(checkinUrl),
      PersonReference(listOf(PersonReference.PersonIdentifier("CRN", checkin.offender.crn!!))),
    )
    return event
  }

  companion object {
    private val LOG = org.slf4j.LoggerFactory.getLogger(OffenderCheckinExpiryJob::class.java)
  }
}
