package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import jakarta.persistence.EntityManager
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinSchedule
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.nextCheckinDay
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.streams.asSequence

data class QuestionsReminderInfo(
  val offenderUuid: UUID,
  val contactDetails: ContactDetails,
  val practitionerId: ExternalUserId,
  val expectedCheckinDate: LocalDate,
)

private data class OffenderInfo(
  val crn: CRN,
  val uuid: UUID,
  val practitioner: ExternalUserId,
  override val firstCheckin: LocalDate,
  override val checkinInterval: Duration,
) : CheckinSchedule

/**
 * Job to send reminders to practitioners about adding custom questions to upcoming checkins.
 * Reminders are sent 1 day or 4 days before the checkin's due date.
 */
@Component
@ConditionalOnProperty(
  prefix = "app.scheduling.v2-practitioner-custom-questions-reminder",
  name = ["enabled"],
  havingValue = "true",
  matchIfMissing = true,
)
class CustomQuestionsReminderJob(
  private val clock: Clock,
  private val offenderRepository: OffenderV2Repository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val notificationService: NotificationV2Service,
  private val jobLogRepository: JobLogV2Repository,
  private val transactionTemplate: TransactionTemplate,
  private val entityManager: EntityManager,
) {

  @Scheduled(cron = "\${app.scheduling.v2-practitioner-custom-questions-reminder.cron}")
  @SchedulerLock(
    name = "V2 Practitioner Custom Questions Reminder Job",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT30M",
  )
  fun process() {
    val now = clock.instant()
    val today = clock.today()
    val reminderWindowStart = today.atStartOfDay(clock.zone).toInstant()

    LOGGER.info("Practitioner Custom Questions Reminder Job started for {}", today)

    val logEntry = transactionTemplate.execute {
      val entry = JobLogV2(jobType = "V2_PRACTITIONER_CUSTOM_QUESTIONS_REMINDER", createdAt = now)
      jobLogRepository.saveAndFlush(entry)
      entry
    }!!

    var totalReminded = 0
    try {
      val offenderInfo = transactionTemplate.execute {
        offenderRepository.findEligibleForPractitionerCustomQuestionsReminder(
          today = today,
          notificationType = NotificationType.PractitionerCustomQuestionsReminder.name,
          reminderWindowStart = reminderWindowStart,
        ).use { stream ->
          stream.asSequence()
            .chunked(INdiliusApiClient.MAX_BATCH_SIZE)
            .map { batch ->
              val infosBatch = batch.map { OffenderInfo(it.crn, it.uuid, it.practitionerId, it.firstCheckin, it.checkinInterval) }
              entityManager.flush()
              entityManager.clear()
              infosBatch
            }.toList()
        }
      } ?: emptyList()

      val sendable = mutableListOf<QuestionsReminderInfo>()
      val unsendable = mutableListOf<CRN>()
      for (batch in offenderInfo) {
        val crns = batch.map<OffenderInfo, String> { it.crn }
        val crnToDetails = try {
          ndiliusApiClient.getContactDetailsForMultiple(crns).associateBy { it.crn }
        } catch (e: Exception) {
          LOGGER.warn("Failed to fetch contact details for batch of {} CRNs", batch.size, e)
          unsendable.addAll(crns)
          continue
        }
        for (info in batch) {
          val added = crnToDetails[info.crn]?.let {
            sendable.add(QuestionsReminderInfo(info.uuid, it, info.practitioner, nextCheckinDay(info, today)))
          }
          if (added == null) {
            unsendable.add(info.crn)
          }
        }
      }

      for (info in sendable) {
        if (trySendReminder(info)) {
          totalReminded++
        }
      }
      if (unsendable.size > 0) {
        LOGGER.warn("Failed to send custom questions reminder to practitioners for {} CRNs: {}", unsendable.size, unsendable)
      }
    } catch (e: Exception) {
      LOGGER.error("V2 Practitioner Custom Questions Reminder Job failed", e)
    }

    val endTime = clock.instant()
    transactionTemplate.execute {
      logEntry.endedAt = endTime
      jobLogRepository.saveAndFlush(logEntry)
    }

    LOGGER.info(
      "V2 Practitioner Custom Questions Reminder Job completed: reminders sent: {}, took {}",
      totalReminded,
      Duration.between(now, endTime),
    )
  }

  private fun trySendReminder(
    info: QuestionsReminderInfo,
  ): Boolean {
    try {
      notificationService.sendPractitionerCustomQuestionsReminder(info)
      return true
    } catch (e: Exception) {
      LOGGER.warn(
        "Failed to send custom questions reminder to practitioner {}, regarding CRN={}",
        info.practitionerId,
        info.contactDetails.crn,
        e,
      )
    }
    return false
  }

  companion object {
    private val LOGGER = logger<CustomQuestionsReminderJob>()
  }
}
