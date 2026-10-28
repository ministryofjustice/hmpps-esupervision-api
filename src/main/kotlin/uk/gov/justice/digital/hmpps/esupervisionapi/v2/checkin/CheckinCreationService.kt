package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PartialCheckinCreatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotificationService
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.Period
import java.util.UUID

class BatchCheckinCreationException(val checkins: List<Pair<OffenderCheckin, PartialCheckinCreatedEvent>>, cause: Exception) : RuntimeException("Failed to batch create checkins", cause)

/**
 * Checkin Creation Service
 * Single source of truth for creating checkins
 * Used by both automated job and manual/DEBUG endpoints
 */
@Service
class CheckinCreationService(
  private val clock: Clock,
  private val offenderRepository: OffenderRepository,
  private val checkinRepository: OffenderCheckinRepository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val notificationService: NotificationService,
  private val eventAuditService: EventAuditService,
  @param:Value("\${app.scheduling.checkin-notification.window:72h}") private val checkinWindow: Duration,
  private val transactionTemplate: TransactionTemplate,
  private val checkinPersistenceService: CheckinPersistenceService,
) {

  val checkinWindowPeriod = Period.ofDays(checkinWindow.toDays().toInt())

  /**
   * Create a checkin for an offender
   * @param offenderUuid Offender UUID
   * @param dueDate Due date for checkin
   * @param createdBy Who created the checkin (e.g., "SYSTEM", practitioner ID)
   * @return Created checkin
   */
  fun createCheckin(
    offenderUuid: UUID,
    dueDate: LocalDate,
    createdBy: ExternalUserId,
  ): OffenderCheckin {
    val offender =
      offenderRepository.findByUuid(offenderUuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Offender not found: $offenderUuid")
      }

    return createCheckinForOffender(offender, dueDate, createdBy)
  }

  /**
   * Create a checkin for an offender. Attempts to fetch contact details from NDelius.
   *
   * @param offender Offender entity
   * @param dueDate Due date for checkin
   * @param createdBy Who created the checkin
   * @return Created checkin
   */
  fun createCheckinForOffender(
    offender: Offender,
    dueDate: LocalDate,
    createdBy: ExternalUserId,
  ): OffenderCheckin {
    val contactDetails = ndiliusApiClient.getContactDetails(offender.crn)
      ?: throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to fetch contact details for CRN=${offender.crn}")
    return createCheckinForOffender(offender, dueDate, createdBy, contactDetails)
  }

  /**
   * Create a checkin for an offender. If the offender has an upcoming (SCHEDULED) check-in for
   * [dueDate], that check-in is activated instead, keeping its UUID.
   * @param offender Offender entity
   * @param dueDate Due date for checkin
   * @param createdBy Who created the checkin
   * @param contactDetails Contact details for offender
   * @return Created checkin
   */
  fun createCheckinForOffender(
    offender: Offender,
    dueDate: LocalDate,
    createdBy: ExternalUserId,
    contactDetails: ContactDetails,
  ): OffenderCheckin {
    val scheduled = checkinRepository.findScheduledForOffenders(listOf(offender.id), dueDate).singleOrNull()
    val checkin = newOrActivatedCheckin(offender, dueDate, createdBy, scheduled)

    val event = PartialCheckinCreatedEvent(
      offenderId = checkin.offender.id,
      practitionerId = checkin.createdBy,
      checkin = checkin.dto(contactDetails, clock = clock, checkinWindow = checkinWindowPeriod),
      offenderContactPreference = checkin.offender.contactPreference,
      currentEvent = checkin.offender.currentEvent,
      checkinMode = offender.mode,
    )

    checkinPersistenceService.checkinCreation(checkin, event)

    LOGGER.info(
      "Created checkin {} for offender CRN={} with due date {} by {}",
      checkin.uuid,
      offender.crn,
      dueDate,
      createdBy,
    )

    return checkin
  }

  /**
   * @param checkins List of checkins to create
   * @return List of saved checkins
   * @throws BatchCheckinCreationException
   */
  @Transactional
  fun createCheckins(checkins: List<Pair<OffenderCheckin, PartialCheckinCreatedEvent>>) {
    if (checkins.isEmpty()) return
    try {
      for ((checkin, event) in checkins) {
        checkinPersistenceService.checkinCreation(checkin, event)
      }
    } catch (e: Exception) {
      throw BatchCheckinCreationException(checkins, e)
    }
  }

  /**
   * Prepare a checkin entity for an offender (used by job for batch processing)
   * Does not save to database - call batchCreateCheckins() to persist
   * @param offender Offender entity
   * @param dueDate Due date for checkin
   * @param scheduled the offender's upcoming check-in for [dueDate], if any, to activate instead of creating a new one
   * @return Checkin entity ready to save
   */
  fun prepareCheckinForOffender(offender: Offender, dueDate: LocalDate, scheduled: OffenderCheckin? = null): OffenderCheckin = newOrActivatedCheckin(offender, dueDate, "SYSTEM", scheduled)

  /**
   * A CREATED check-in, not yet saved: [scheduled] activated, or a new one. Activation keeps the
   * UUID NDelius already knows the check-in by; saving it fires the same triggers as an insert
   * (outbox item, question assignment). It takes the creation time and creator of the activation,
   * as a new check-in would.
   */
  private fun newOrActivatedCheckin(offender: Offender, dueDate: LocalDate, createdBy: ExternalUserId, scheduled: OffenderCheckin?): OffenderCheckin {
    if (scheduled == null) {
      return OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.CREATED,
        dueDate = dueDate,
        createdAt = clock.instant(),
        createdBy = createdBy,
      )
    }
    require(scheduled.status == CheckinStatus.SCHEDULED && scheduled.dueDate == dueDate) {
      "Check-in ${scheduled.uuid} is ${scheduled.status} for ${scheduled.dueDate}, expected SCHEDULED for $dueDate"
    }
    LOGGER.info("Activating scheduled checkin {} due {}", scheduled.uuid, dueDate)
    return scheduled.apply {
      this.offender = offender
      status = CheckinStatus.CREATED
      createdAt = clock.instant()
      this.createdBy = createdBy
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(CheckinCreationService::class.java)
  }
}
