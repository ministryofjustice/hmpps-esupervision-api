package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import jakarta.transaction.Transactional
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.events.CheckinAdditionalInformation
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DOMAIN_EVENT_VERSION
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DomainEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.events.PersonReference
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PractitionerCheckinMissedMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.BulkNotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
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

  private val cachingPractitionersRepository = CachingPractitionerRepository(practitionerRepository)

  @Scheduled(cron = "\${app.scheduling.offender-checkin-expiry.cron}")
  @SchedulerLock(
    name = "CheckinNotifier - mark checkins as expired",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT10M",
  )
  @Transactional
  fun process() {
    val now = clock.instant()
    val cutoff = cutoffDate(clock, checkinWindow)

    LOG.info("processing starts. checkins with due_date <= cutoff=$cutoff will be marked as expired.")

    val logEntry = JobLog(JobType.CHECKIN_EXIPIRED_NOTIFICATIONS_JOB, now)
    jobLogRepository.saveAndFlush(logEntry)

    var totalUpdates = 0
    try {
      val lowerBound = cutoff
        .minus(Period.ofDays(2))
        .atStartOfDay(clock.zone).toInstant()

      val chunkSize = 100
      val context = BulkNotificationContext(logEntry.reference())
      checkinRepository.findAllAboutToExpire(cutoff, lowerBound = lowerBound)
        .asSequence()
        .chunked(chunkSize)
        .forEach { checkins ->
          LOG.info("processing chunk of ${checkins.size} checkins")
          notifyPractitioner(checkins, context)
        }

      val result = checkinRepository.updateStatusToExpired(cutoff, lowerBound = lowerBound)
      LOG.info("updated {} checkins", result)
      totalUpdates += result
    } catch (e: Exception) {
      LOG.warn("job failure", e)
    }

    val endTime = clock.instant()
    logEntry.endedAt = endTime
    jobLogRepository.saveAndFlush(logEntry)
    cachingPractitionersRepository.cache.clear()

    LOG.info("processing ends. total updates={}, took={}", totalUpdates, Duration.between(now, clock.instant()))
  }

  private fun notifyPractitioner(
    checkins: List<OffenderCheckin>,
    context: BulkNotificationContext,
  ) {
    try {
      for (checkin in checkins) {
        LOG.debug("sending expiry notification for checkin {}, status={}", checkin.uuid, checkin.status)
        val practitioner = cachingPractitionersRepository.expectById(checkin.offender.practitioner)
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

  private fun checkinExpiredEvent(checkin: OffenderCheckin): DomainEvent {
    assert(checkin.offender.crn != null)
    val checkinUrl = appConfig.checkinDashboardUrl(checkin.uuid).toURL()
    val event = DomainEvent(
      DomainEventType.CHECKIN_EXPIRED.type,
      version = DOMAIN_EVENT_VERSION,
      detailUrl = null,
      occurredAt = clock.instant().atZone(clock.zone),
      description = DomainEventType.CHECKIN_EXPIRED.description,
      additionalInformation = CheckinAdditionalInformation(checkinUrl.toString()),
      PersonReference(listOf(PersonReference.PersonIdentifier("CRN", checkin.offender.crn!!))),
    )
    return event
  }

  companion object {
    private val LOG = org.slf4j.LoggerFactory.getLogger(OffenderCheckinExpiryJob::class.java)
  }
}

/**
 * Returns the upper inclusive bound for checkins that should be marked as expired.
 */
internal fun cutoffDate(clock: Clock, checkinWindow: Duration): LocalDate {
  val today = clock.today()
  val cutoff = today.minus(Period.ofDays(checkinWindow.toDays().toInt()))
  return cutoff
}

/**
 * Note: Not thread safe
 */
private class CachingPractitionerRepository(
  private val delegate: PractitionerRepository,
  val cache: MutableMap<ExternalUserId, Practitioner> = mutableMapOf(),
) : PractitionerRepository {
  override fun findById(id: ExternalUserId): Practitioner? {
    if (cache.containsKey(id)) return cache[id]
    val result = delegate.findById(id)
    if (result != null) cache[id] = result
    return result
  }
}
