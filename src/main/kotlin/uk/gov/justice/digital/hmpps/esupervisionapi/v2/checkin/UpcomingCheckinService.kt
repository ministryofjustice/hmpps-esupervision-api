package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventNotes
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinMode
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.CheckinCreationJob
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * ESUP-2223: keeps an offender's next check-in recorded in NDelius before it is due, as an
 * upcoming (SCHEDULED) check-in. Only the next check-in is recorded: while a check-in is open
 * (CREATED), the one after it is scheduled once that one is submitted or missed.
 *
 * Scheduling never fails the change that triggered it. A gap it leaves is filled when the check-in
 * is created on its due date, which records it as scheduled first (see
 * [uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinPersistenceService.checkinCreation]).
 */
@Service
class UpcomingCheckinService(
  private val offenderRepository: OffenderRepository,
  private val checkinRepository: OffenderCheckinRepository,
  private val checkinDeliusEventService: CheckinDeliusEventService,
  private val appConfig: AppConfig,
  private val clock: Clock,
  private val entityManager: EntityManager,
  private val jobLogRepository: JobLogRepository,
  transactionManager: PlatformTransactionManager,
) {
  private val newTransaction = TransactionTemplate(transactionManager).apply {
    propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
  }

  fun enabled() = appConfig.enabledFeatures.contains(Feature.ESUP_2223)

  /**
   * Schedule the offender's next check-in, if it isn't scheduled yet. Inside a transaction this
   * runs once the transaction commits, so it sees the change that triggered it.
   *
   * @param eventNumber the offender's active event, if the caller knows it; otherwise the one on record
   */
  fun scheduleNext(offenderId: Long, eventNumber: Long? = null) {
    if (!enabled()) return
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
        object : TransactionSynchronization {
          override fun afterCommit() = scheduleNextNow(offenderId, eventNumber, includeToday = null)
        },
      )
    } else {
      scheduleNextNow(offenderId, eventNumber, includeToday = null)
    }
  }

  /**
   * For the check-in creation job, before it creates today's check-ins: today counts as the next
   * check-in date, as the job is about to create (activate) it.
   */
  fun scheduleNextBeforeCreation(offenderId: Long) {
    if (!enabled()) return
    scheduleNextNow(offenderId, eventNumber = null, includeToday = true)
  }

  private fun scheduleNextNow(offenderId: Long, eventNumber: Long?, includeToday: Boolean?) {
    try {
      newTransaction.executeWithoutResult { scheduleNextInTransaction(offenderId, eventNumber, includeToday) }
    } catch (e: Exception) {
      LOGGER.error("Failed to schedule the next check-in for offender id={}", offenderId, e)
    }
  }

  /** @param includeToday whether today can be the next check-in date; null: only if today's creation job hasn't started */
  private fun scheduleNextInTransaction(offenderId: Long, eventNumber: Long?, includeToday: Boolean?) {
    // Locking the offender serialises this with stopping check-ins, which cancels upcoming check-ins.
    val offender = offenderRepository.findByIdForUpdate(offenderId) ?: return
    // Under open-in-view this joins the request's persistence context, which may hold the offender
    // as it was before the change that triggered this.
    entityManager.refresh(offender)
    if (offender.status != OffenderStatus.VERIFIED) return
    val today = clock.today()
    val event = eventNumber ?: offender.currentEvent

    val scheduled = checkinRepository.findAllByOffenderAndStatus(offender, CheckinStatus.SCHEDULED).singleOrNull()
    if (scheduled != null) {
      if (scheduled.dueDate >= today) return
      // Its due date passed without it being created: no active event, or the schedule changed.
      scheduled.status = CheckinStatus.CANCELLED
      checkinRepository.save(scheduled)
      checkinDeliusEventService.record(DomainEventType.V2_CHECKIN_CANCELLED, scheduled, CheckinDeliusEventNotes.cancelled(NOT_CREATED_REASON), event)
      LOGGER.info("Cancelled checkin {} for CRN {}: not created on its due date {}", scheduled.uuid, offender.crn, scheduled.dueDate)
    }

    if (checkinRepository.findAllByOffenderAndStatus(offender, CheckinStatus.CREATED).isNotEmpty()) return

    // After today's creation job, a check-in for today won't be created, so it can't be scheduled.
    val from = if (includeToday ?: !creationJobStarted(today)) today else today.plusDays(1)
    val dueDate = nextCheckinDate(offender, from) ?: return
    val checkin = checkinRepository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.SCHEDULED,
        dueDate = dueDate,
        createdAt = clock.instant(),
        createdBy = "SYSTEM",
      ),
    )
    checkinDeliusEventService.record(DomainEventType.V2_CHECKIN_SCHEDULED, checkin, CheckinDeliusEventNotes.scheduled(dueDate), event, dueDate)
    LOGGER.info("Scheduled checkin {} for CRN {} due {}", checkin.uuid, offender.crn, dueDate)
  }

  /** The offender's next check-in date on or after [from] that doesn't have a check-in yet. */
  private fun nextCheckinDate(offender: Offender, from: LocalDate): LocalDate? {
    val firstCheckin = offender.firstCheckin ?: return null
    return when (offender.mode) {
      CheckinMode.AD_HOC -> firstCheckin.takeIf { it >= from && !hasCheckin(offender, it) }
      CheckinMode.SCHEDULED -> {
        if (offender.checkinInterval == null) return null
        val next = nextCheckinDay(offender, from, CheckinScheduleLowerBound.INCLUDE_TODAY)
        if (hasCheckin(offender, next)) nextCheckinDay(offender, next) else next
      }
    }
  }

  private fun creationJobStarted(today: LocalDate) = jobLogRepository.existsByJobTypeAndCreatedAtGreaterThanEqual(CheckinCreationJob.JOB_TYPE, today.atStartOfDay(clock.zone).toInstant())

  private fun hasCheckin(offender: Offender, date: LocalDate) = checkinRepository.findAllByOffenderAndDueDate(offender, date).any { it.status in STATUSES_HOLDING_DATE }

  companion object {
    private val LOGGER = logger<UpcomingCheckinService>()

    /** A check-in in one of these statuses takes its date: no other check-in is scheduled for it. */
    private val STATUSES_HOLDING_DATE = setOf(CheckinStatus.CREATED, CheckinStatus.SUBMITTED, CheckinStatus.REVIEWED, CheckinStatus.EXPIRED)

    // TODO ESUP-2223: wording to be agreed with product
    const val NOT_CREATED_REASON = "The check in was not opened on its due date"
  }
}
