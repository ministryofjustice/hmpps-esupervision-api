package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
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

/** V2 Checkin Expiry Job Marks overdue checkins as EXPIRED Core feature - always enabled */
@Component
class V2CheckinExpiryJob(
  private val clock: Clock,
  private val checkinRepository: OffenderCheckinV2Repository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val notificationService: NotificationV2Service,
  private val jobLogRepository: JobLogV2Repository,
  private val transactionTemplate: TransactionTemplate,
  @Value("\${app.scheduling.v2-checkin-expiry.grace-period-days:3}")
  private val gracePeriodDays: Int,
  private val eventAuditService: EventAuditV2Service,
) {
  @Scheduled(cron = "\${app.scheduling.v2-checkin-expiry.cron}")
  @SchedulerLock(
    name = "V2 Checkin Expiry Job",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT30M",
  )
  fun process() {
    val now = clock.instant()
    val today = LocalDate.now(clock)
    val expiryDate = today.minusDays(gracePeriodDays.toLong())

    LOGGER.info(
      "V2 Checkin Expiry Job started: marking checkins as EXPIRED with due date < {}",
      expiryDate,
    )

    val logEntry =
      transactionTemplate.execute {
        val entry = JobLogV2(jobType = "V2_CHECKIN_EXPIRY", createdAt = now)
        jobLogRepository.saveAndFlush(entry)
        entry
      }!!

    var totalExpired = 0

    try {
      // Find checkins eligible for expiry - separate transaction
      val checkins =
        transactionTemplate.execute {
          checkinRepository.findEligibleForExpiry(expiryDate).use { stream ->
            stream.asSequence().toList()
          }
        }!!

      LOGGER.info("Found {} checkins eligible for expiry", checkins.size)

      if (checkins.isNotEmpty()) {
        // Batch update all checkin statuses to EXPIRED - separate transaction
        transactionTemplate.execute {
          checkins.forEach { it.status = CheckinV2Status.EXPIRED }
          checkinRepository.saveAll(checkins)
        }
        totalExpired = checkins.size

        LOGGER.info("Marked {} checkins as EXPIRED", totalExpired)

        // Fetch contact details in batches for notifications (practitioner emails only) - NO
        // transaction
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
              notificationService.sendCheckinExpiredNotifications(checkin, contactDetails)
              sentNotifications.add(Pair(checkin, contactDetails))
            } else {
              notSentNotifications.add(index)
            }

            LOGGER.info(
              "Expiry notifications for checkin {} (offender {}, due date was {}): {}",
              checkin.uuid,
              checkin.offender.crn,
              checkin.dueDate,
              if (contactDetails == null) "not sent (missing details)" else "sent",
            )
          } catch (e: Exception) {
            LOGGER.warn("Failed to send expiry notifications for checkin {}", checkin.uuid, e)
            notSentNotifications.add(index)
          }
        }

        eventAuditService.recordCheckinExpired(sentNotifications)
        eventAuditService.recordCheckinExpired(notSentNotifications.map { Pair(checkins[it], null) })
      }
    } catch (e: Exception) {
      LOGGER.error("V2 Checkin Expiry Job failed", e)
    }

    val endTime = clock.instant()
    transactionTemplate.execute {
      logEntry.endedAt = endTime
      jobLogRepository.saveAndFlush(logEntry)
    }

    LOGGER.info(
      "V2 Checkin Expiry Job completed: expired={}, took={}",
      totalExpired,
      Duration.between(now, endTime),
    )
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(V2CheckinExpiryJob::class.java)
  }
}
