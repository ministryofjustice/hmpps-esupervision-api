package uk.gov.justice.digital.hmpps.esupervisionapi.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.BulkNotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.NotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinService
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CreateCheckinRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

internal data class NotifierContext(
  val today: ZonedDateTime,
  val notificationLeadTime: Duration,
  val checkinDate: ZonedDateTime = today.plus(notificationLeadTime),
  val notificationContext: NotificationContext,
) {
  fun isCheckinDay(offender: Offender): Boolean {
    val firstCheckin = offender.firstCheckin?.withZoneSameInstant(ZoneId.of("UTC"))
    if (firstCheckin != null) {
      val delta = Duration.between(firstCheckin, checkinDate)
      return delta.toDays() / offender.checkinInterval.toDays() == 0L
    }
    return false
  }
}

@Component
class CheckinNotifier(
  private val offenderRepository: OffenderRepository,
  private val offenderCheckinService: OffenderCheckinService,
  private val clock: Clock,
) {

  val notificationLeadTime: Duration = Duration.ofDays(0)

  /**
   * This method is meant to be called via a scheduling mechanism and not directly.
   *
   * It processes relevant Offender records, determines whether a notification should be
   * sent and attempts to send it.
   *
   * We process a stream of Offenders but catch individual errors - we don't want to
   * abort the transaction because of a single error. We want the notification ID
   * saved in the OffenderCheckin record.
   */
  @Scheduled(cron = "#{@appConfig.checkinNotificationCron}")
  @SchedulerLock(
    name = "CheckinNotifier - send notifications",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT10M",
  )
  @Transactional
  fun process() {
    LOG.info("processing starts")

    val now = clock.instant()
    val startOfDayUtc: ZonedDateTime = startOfDay(now, ZoneOffset.UTC)

    val notificationContext = BulkNotificationContext(UUID.randomUUID())

    val context = NotifierContext(
      startOfDayUtc,
      notificationLeadTime,
      notificationContext = notificationContext,
    )

    val offenders = offenderRepository.findAllCheckinNotificationCandidates(
      startOfDayUtc,
      startOfDayUtc.plusDays(1),
    )
    var numProcessed = 0
    var numErrors = 0
    var numNotifAttempts = 0

    for (offender in offenders) {
      try {
        val checkin = processOffender(offender, context)
        numProcessed += 1
        numNotifAttempts += if ((checkin?.notifications?.results?.size ?: 0) > 0) 1 else 0
      } catch (e: Exception) {
        LOG.warn("Error processing offender=${offender.uuid}", e)
        numErrors += 1
      }
    }

    LOG.info(
      "processing ends. total processed={}, failed={}, notifications={}, took={}",
      numProcessed,
      numErrors,
      numNotifAttempts,
      Duration.between(now, clock.instant()),
    )
  }

  internal fun processOffender(offender: Offender, context: NotifierContext): OffenderCheckinDto? {
    // assumptions:
    // - no `OffenderCheckin` records with due date between `context.today`, context.potentialCheckin
    val isCheckinDay = context.isCheckinDay(offender)
    LOG.debug("is offender={} due for a checkin? {}", offender.uuid, if (isCheckinDay) "yes" else "no")
    if (isCheckinDay) {
      val checkin = offenderCheckinService.createCheckin(
        CreateCheckinRequest(
          offender.practitioner.uuid,
          offender.uuid,
          context.checkinDate.toLocalDate(),
        ),
        context.notificationContext,
      )
      return checkin
    }

    return null
  }

  private fun startOfDay(now: Instant, offset: ZoneOffset): ZonedDateTime = now.atZone(offset)
    .withHour(0)
    .withMinute(0)
    .withSecond(0)
    .withNano(0)

  companion object {
    private val LOG = LoggerFactory.getLogger(CheckinNotifier::class.java)
  }
}
