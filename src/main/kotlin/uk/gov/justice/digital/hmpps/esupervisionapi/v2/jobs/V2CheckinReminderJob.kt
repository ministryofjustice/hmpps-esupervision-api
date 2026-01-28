package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlin.streams.asSequence

/** V2 Checkin Reminder Job Sends notifications for checkins due tomorrow */
@Component
class V2CheckinReminderJob(
  private val clock: Clock,
  private val checkinRepository: OffenderCheckinV2Repository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val notificationService: NotificationV2Service,
  private val jobLogRepository: JobLogV2Repository,
  private val transactionTemplate: TransactionTemplate,
  private val eventAuditService: EventAuditV2Service,
) {

  @Scheduled(cron = "\${app.scheduling.v2-checkin-reminder.cron}")
  @SchedulerLock(
    name = "V2 Checkin Reminder Job",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT30M",
  )
  fun process() {
    val now = clock.instant()
    val today = LocalDate.now(clock)
    val checkinDueDate = today

    LOGGER.info(
      "V2 Checkin Reminder Job started: processing checkins due on {}",
      checkinDueDate,
    )

    val logEntry =
      transactionTemplate.execute {
        val entry = JobLogV2(jobType = "V2_CHECKIN_REMINDER", createdAt = now)
        jobLogRepository.saveAndFlush(entry)
        entry
      }!!

    var totalReminded = 0

    try {
      // Find checkins eligible for reminder - separate transaction
      val checkins =
        transactionTemplate.execute {
          checkinRepository.findEligibleForReminder(checkinDueDate).use { stream ->
            stream.asSequence().toList()
          }
        }!!

      LOGGER.info("Found {} checkins eligible for reminder", checkins.size)

      if (checkins.isNotEmpty()) {
        totalReminded = checkins.size

        // Fetch contact details in batches - NO transaction
        val crns = checkins.map { it.offender.crn }.distinct()
        val contactDetailsMap = mutableMapOf<String, ContactDetails>()

        crns.chunked(NdiliusApiClient.MAX_BATCH_SIZE).forEachIndexed { batchIndex, batchCrns ->
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
