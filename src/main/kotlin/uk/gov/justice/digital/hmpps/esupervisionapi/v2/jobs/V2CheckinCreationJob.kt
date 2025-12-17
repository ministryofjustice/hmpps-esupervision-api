package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlin.streams.asSequence

/**
 * V2 Checkin Creation Job
 * Creates checkins for verified offenders based on their schedule
 * Core feature - always enabled
 */
@Component
class V2CheckinCreationJob(
  private val clock: Clock,
  private val offenderRepository: OffenderV2Repository,
  private val checkinRepository: OffenderCheckinV2Repository,
  private val ndiliusApiClient: NdiliusApiClient,
  private val checkinCreationService: CheckinCreationService,
  private val notificationService: NotificationV2Service,
  private val jobLogRepository: JobLogV2Repository,
  private val transactionTemplate: TransactionTemplate,
) {
  @Scheduled(cron = "\${app.scheduling.v2-checkin-creation.cron}")
  @SchedulerLock(
    name = "V2 Checkin Creation Job",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT30M",
  )
  fun process() {
    val now = clock.instant()
    val today = LocalDate.now(clock)

    LOGGER.info("V2 Checkin Creation Job started: creating checkins for date={}", today)

    val logEntry = transactionTemplate.execute {
      val entry = JobLogV2(jobType = "V2_CHECKIN_CREATION", createdAt = now)
      jobLogRepository.saveAndFlush(entry)
      entry
    }!!

    var totalCreated = 0

    try {
      val lowerBound = today
      val upperBound = today.plusDays(1)

      val offenders = transactionTemplate.execute {
        offenderRepository.findEligibleForCheckinCreation(lowerBound, upperBound).use { stream ->
          stream.asSequence().toList()
        }
      }!!

      LOGGER.info("Found {} offenders eligible for checkin creation", offenders.size)

      if (offenders.isNotEmpty()) {
        // Batch check which offenders already have checkins for today - avoid N+1 queries
        val existingCheckins = transactionTemplate.execute {
          checkinRepository.findByOffendersAndDueDate(offenders, today)
        }!!
        val offendersWithCheckins = existingCheckins.map { it.offender.uuid }.toSet()
        LOGGER.info("Found {} offenders with existing checkins for {}", offendersWithCheckins.size, today)

        val crns = offenders.map { it.crn }
        val offenderMap = offenders.associateBy { it.crn }

        crns.chunked(NdiliusApiClient.MAX_BATCH_SIZE).forEachIndexed { batchIndex, batchCrns ->
          LOGGER.info("Processing batch {}: {} CRNs", batchIndex + 1, batchCrns.size)

          val contactDetailsMap = ndiliusApiClient.getContactDetailsForMultiple(batchCrns)
            .associateBy { it.crn }

          LOGGER.info("Fetched contact details for {}/{} CRNs", contactDetailsMap.size, batchCrns.size)

          val missingCrns = batchCrns.filter { it !in contactDetailsMap }
          if (missingCrns.isNotEmpty()) {
            LOGGER.warn("Contact details not found for {} CRNs: {}", missingCrns.size, missingCrns.take(10))
          }

          // Collect checkins to create in batch
          val checkinsToCreate = mutableListOf<Pair<OffenderCheckinV2, ContactDetails>>()

          batchCrns.forEach { crn ->
            val offender = offenderMap[crn]
            val contactDetails = contactDetailsMap[crn]

            if (offender != null && contactDetails != null) {
              // Skip if offender already has a checkin for today (in-memory check, no DB query)
              if (offender.uuid !in offendersWithCheckins) {
                val checkinData = checkinCreationService.prepareCheckinForOffender(offender, today)
                if (checkinData != null) {
                  checkinsToCreate.add(checkinData to contactDetails)
                }
              } else {
                LOGGER.debug("Checkin already exists for offender {} on date {}", offender.crn, today)
              }
            } else if (offender != null) {
              LOGGER.warn("Skipping checkin creation for offender {}: contact details not found", crn)
            }
          }

          // Batch insert all checkins using CheckinCreationService
          if (checkinsToCreate.isNotEmpty()) {
            val savedCheckins = checkinCreationService.batchCreateCheckins(checkinsToCreate.map { it.first })
            totalCreated += savedCheckins.size

            // Send notifications for created checkins
            savedCheckins.forEachIndexed { index, checkin ->
              val contactDetails = checkinsToCreate[index].second
              try {
                notificationService.sendCheckinCreatedNotifications(checkin, contactDetails)
              } catch (e: Exception) {
                LOGGER.error("Failed to send notifications for checkin {}", checkin.uuid, e)
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      LOGGER.error("V2 Checkin Creation Job failed", e)
    }

    val endTime = clock.instant()
    transactionTemplate.execute {
      logEntry.endedAt = endTime
      jobLogRepository.saveAndFlush(logEntry)
    }

    LOGGER.info(
      "V2 Checkin Creation Job completed: created={}, took={}",
      totalCreated,
      Duration.between(now, endTime),
    )
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(V2CheckinCreationJob::class.java)
  }
}
