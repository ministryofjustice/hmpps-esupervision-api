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
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PartialCheckinCreatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
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
   * Create a checkin for an offender
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
    val checkin =
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.CREATED,
        dueDate = dueDate,
        createdAt = clock.instant(),
        createdBy = createdBy,
      )

    val event = PartialCheckinCreatedEvent(
      offenderId = checkin.offender.id,
      practitionerId = checkin.createdBy,
      checkin = checkin.dto(contactDetails, clock = clock, checkinWindow = checkinWindowPeriod),
      offenderContactPreference = checkin.offender.contactPreference,
      currentEvent = checkin.offender.currentEvent,
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
   * @return Checkin entity ready to save
   */
  fun prepareCheckinForOffender(offender: Offender, dueDate: LocalDate): OffenderCheckin = OffenderCheckin(
    uuid = UUID.randomUUID(),
    offender = offender,
    status = CheckinStatus.CREATED,
    dueDate = dueDate,
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
  )

  companion object {
    private val LOGGER = LoggerFactory.getLogger(CheckinCreationService::class.java)
  }
}
