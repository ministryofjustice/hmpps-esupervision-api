package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import java.time.Clock
import java.time.Duration

/** V2 Checkin Reminder Job Sends notifications for check ins that will expire today (day 3 of 3) */
@Component
@ConditionalOnProperty(
  prefix = "app.scheduling.v2-checkin-reminder", 
  name = ["enabled"], 
  havingValue = "true", 
  matchIfMissing = true
)
class V2CheckinReminderJob(
  private val clock: Clock,
  private val checkinRepository: OffenderCheckinV2Repository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val notificationService: NotificationV2Service,
  private val jobLogRepository: JobLogV2Repository,
  private val transactionTemplate: TransactionTemplate,
  private val eventAuditService: EventAuditV2Service,
  private val genericNotificationV2Repository: GenericNotificationV2Repository,
) {

 @Scheduled(cron = "\${app.scheduling.v2-checkin-reminder.cron}")
 @SchedulerLock(
   name = "V2 Checkin Reminder Job",
   lockAtLeastFor = "PT5S",
   lockAtMostFor = "PT30M",
 )
  fun process() {
    val now = clock.instant()
    val today = clock.today()
    // date for checkins that started 2 days ago (day 1 out of 3)
    val checkinStartDate = today.minusDays(2)
    //  instant of the check in start date used to check if any reminder notifications have been sent since that date
    val checkinWindowStart = checkinStartDate.atStartOfDay(clock.zone).toInstant()

    LOGGER.info(
      "V2 Checkin Reminder Job started: processing checkins due on {} (check in expiring at the end of today)",
      checkinStartDate,
    )

    val logEntry =
      transactionTemplate.execute {
        val entry = JobLogV2(jobType = "V2_CHECKIN_REMINDER", createdAt = now)
        jobLogRepository.saveAndFlush(entry)
        entry
      }!!

    var totalReminded = 0

    // Find checkins eligible for reminder (if they were already sent one today, don't send again)
    // the prod job should only run once a day but local/dev environments could be sending duplicate reminders if they're set more frequently
    try {
      val checkins = transactionTemplate.execute {
        checkinRepository.findEligibleForReminder(
          checkinStartDate = checkinStartDate,
          notificationType = NotificationType.OffenderCheckinReminder.name,
          checkinWindowStart = checkinWindowStart,
        ).use { stream ->
          stream.toList()
        }
      }!!

      LOGGER.info("Found {} checkins eligible for reminder", checkins.size)

      if (checkins.isNotEmpty()) {
        totalReminded = checkins.size

        // Fetch contact details in batches - NO transaction
        val crns = checkins.map { it.offender.crn }.distinct()
        val contactDetailsMap = mutableMapOf<String, ContactDetails>()

        crns.chunked(INdiliusApiClient.MAX_BATCH_SIZE).forEachIndexed { batchIndex, batchCrns ->
          LOGGER.info("Fetching contact details batch {}: {} CRNs", batchIndex + 1, batchCrns.size)
          try {
            val batchDetails =
              ndiliusApiClient.getContactDetailsForMultiple(batchCrns).associateBy { it.crn }
            contactDetailsMap.putAll(batchDetails)
          } catch (e: Exception) {
            LOGGER.warn("Failed to fetch contact details for batch {}", batchIndex + 1, e)
          }
        }

        // Send notifications with pre-fetched contact details - NO transaction
        val sentNotifications = mutableListOf<Pair<OffenderCheckinV2, ContactDetails>>()
        val notSentNotifications = mutableSetOf<Int>()
        checkins.forEachIndexed { index, checkin ->
          try {
            val contactDetails = contactDetailsMap[checkin.offender.crn]
            if (contactDetails != null) {
              notificationService.sendCheckinReminderNotifications(checkin, contactDetails)
              sentNotifications.add(Pair(checkin, contactDetails))
            } else {
              notSentNotifications.add(index)
            }

            LOGGER.info(
              "Reminder notifications for checkin {} (offender {}, due date is {}): {}",
              checkin.uuid,
              checkin.offender.crn,
              checkin.dueDate,
              if (contactDetails == null) "not sent (missing details)" else "sent",
            )
          } catch (e: Exception) {
            LOGGER.warn("Failed to send reminder notification for checkin {}", checkin.uuid, e)
            notSentNotifications.add(index)
          }
        }

        eventAuditService.recordCheckinReminded(sentNotifications)

        if (notSentNotifications.isNotEmpty()) {
          eventAuditService.recordCheckinReminded(
            notSentNotifications.map { Pair(checkins[it], null) },
          )
        }
      }
    } catch (e: Exception) {
      LOGGER.error("V2 Checkin Reminder Job failed", e)
    }

    val endTime = clock.instant()
    transactionTemplate.execute {
      logEntry.endedAt = endTime
      jobLogRepository.saveAndFlush(logEntry)
    }

    LOGGER.info(
      "V2 Checkin Reminder Job completed: reminded={}, took={}",
      totalReminded,
      Duration.between(now, endTime),
    )
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(V2CheckinReminderJob::class.java)
  }
}
