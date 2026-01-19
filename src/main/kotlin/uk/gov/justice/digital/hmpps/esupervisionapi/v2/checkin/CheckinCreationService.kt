package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

class BatchCheckinCreationException(val checkins: List<OffenderCheckinV2>, cause: Exception) : RuntimeException("Failed to batch create checkins", cause)

/**
 * Checkin Creation Service
 * Single source of truth for creating checkins
 * Used by both automated job and manual/DEBUG endpoints
 */
@Service
class CheckinCreationService(
  private val clock: Clock,
  private val offenderRepository: OffenderV2Repository,
  private val checkinRepository: OffenderCheckinV2Repository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val notificationService: NotificationV2Service,
  private val eventAuditService: EventAuditV2Service,
) {

  /**
   * Create a checkin for an offender
   * @param offenderUuid Offender UUID
   * @param dueDate Due date for checkin
   * @param createdBy Who created the checkin (e.g., "SYSTEM", practitioner ID)
   * @return Created checkin
   */
  @Transactional
  fun createCheckin(
    offenderUuid: UUID,
    dueDate: LocalDate,
    createdBy: ExternalUserId,
  ): OffenderCheckinV2 {
    val offender =
      offenderRepository.findByUuid(offenderUuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Offender not found: $offenderUuid")
      }

    return createCheckinForOffender(offender, dueDate, createdBy)
  }

  /**
   * Create a checkin for an offender (internal - used by job for batch processing)
   * @param offender Offender entity
   * @param dueDate Due date for checkin
   * @param createdBy Who created the checkin
   * @return Created checkin
   */
  @Transactional
  fun createCheckinForOffender(
    offender: OffenderV2,
    dueDate: LocalDate,
    createdBy: ExternalUserId,
  ): OffenderCheckinV2 {
    val checkin =
      OffenderCheckinV2(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinV2Status.CREATED,
        dueDate = dueDate,
        createdAt = clock.instant(),
        createdBy = createdBy,
      )

    val saved = checkinRepository.save(checkin)

    LOGGER.info(
      "Created checkin {} for offender {} (CRN={}) with due date {} by {}",
      saved.uuid,
      offender.uuid,
      offender.crn,
      dueDate,
      createdBy,
    )

    // Fetch contact details and send notifications
    val contactDetails =
      try {
        ndiliusApiClient.getContactDetails(offender.crn)
      } catch (e: Exception) {
        LOGGER.warn("Failed to fetch contact details for CRN={}", offender.crn, e)
        null
      }

    if (contactDetails != null) {
      try {
        notificationService.sendCheckinCreatedNotifications(saved, contactDetails)
      } catch (e: Exception) {
        LOGGER.info("Failed to send checkin created notifications for checkin {}", saved.uuid, e)
      }
    } else {
      LOGGER.warn("Skipping notifications for checkin {}: contact details not found", saved.uuid)
    }
    eventAuditService.recordCheckinCreated(checkin, contactDetails)

    return saved
  }

  /**
   * Batch create checkins (used by job for efficiency)
   * @param checkins List of checkins to create
   * @return List of saved checkins
   * @throws BatchCheckinCreationException
   */
  @Transactional
  fun batchCreateCheckins(checkins: List<OffenderCheckinV2>): List<OffenderCheckinV2> {
    if (checkins.isEmpty()) return emptyList()

    return try {
      val savedCheckins = checkinRepository.saveAll(checkins).toList()

      LOGGER.info("Batch created {} checkins", savedCheckins.size)
      savedCheckins.forEach { checkin ->
        LOGGER.debug(
          "Created checkin {} for offender {} with due date {}",
          checkin.uuid,
          checkin.offender.crn,
          checkin.dueDate,
        )
      }

      savedCheckins
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
  fun prepareCheckinForOffender(offender: OffenderV2, dueDate: LocalDate): OffenderCheckinV2 = OffenderCheckinV2(
    uuid = UUID.randomUUID(),
    offender = offender,
    status = CheckinV2Status.CREATED,
    dueDate = dueDate,
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
  )

  companion object {
    private val LOGGER = LoggerFactory.getLogger(CheckinCreationService::class.java)
  }
}
