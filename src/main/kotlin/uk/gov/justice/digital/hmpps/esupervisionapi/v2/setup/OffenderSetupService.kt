package uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup

import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.checkinIneligibilityReason
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.Period
import java.util.Optional
import java.util.UUID
import kotlin.jvm.optionals.getOrElse

@Service
class OffenderSetupPersistenceService(
  private val offenderRepository: OffenderRepository,
  private val checkinCreationService: CheckinCreationService,
  private val clock: Clock,
) {
  data class Result(val checkin: UUID?)

  /**
   *
   */
  @Transactional
  fun completeOffenderSetupAndMaybeCreateCheckin(offender: Offender, contactDetails: ContactDetails?, createCheckin: Boolean): Result {
    require(offender.status == OffenderStatus.VERIFIED) { "Offender must be in VERIFIED status" }
    offenderRepository.save(offender)
    val checkin = if (createCheckin && contactDetails != null) {
      checkinCreationService.createCheckinForOffender(offender, offender.firstCheckin, offender.createdBy, contactDetails)
    } else {
      null
    }
    return Result(checkin = checkin?.uuid)
  }
}

/**
 * V2 Offender Setup Service Handles registration/setup workflow for V2 offenders
 *
 * Key differences from V1:
 * - No PII storage (only CRN)
 * - Uses V2 entities and repositories
 * - Uses V2 notification and audit services
 */
@Service
class OffenderSetupService(
  private val clock: Clock,
  private val offenderRepository: OffenderRepository,
  private val offenderSetupRepository: OffenderSetupRepository,
  private val s3UploadService: S3UploadService,
  private val notificationService: NotificationService,
  private val ndiliusApiClient: INdiliusApiClient,
  private val transactionTemplate: TransactionTemplate,
  @param:Value("\${app.scheduling.checkin-notification.window:72h}") private val checkinWindow: Duration,
  private val offenderSetupPersistenceService: OffenderSetupPersistenceService,
) {

  private val checkinWindowPeriod = Period.ofDays(checkinWindow.toDays().toInt())

  fun findSetupByUuid(uuid: UUID): Optional<OffenderSetup> = offenderSetupRepository.findByUuid(uuid)

  /** Start offender setup (registration) Creates OffenderV2 and OffenderSetupV2 records */
  @Transactional
  internal fun startOffenderSetup(offenderInfo: OffenderInfo): OffenderSetupDto {
    val now = clock.instant()

    val offenderByCrn = offenderRepository.findByCrn(offenderInfo.crn)
    val offender = if (offenderByCrn.isPresent) {
      // somebody tried to onboard this CRN in the past, update the record with submitted options
      val existing = offenderByCrn.get()
      if (existing.status != OffenderStatus.INITIAL) {
        throw BadArgumentException("Offender already exists.")
      }
      existing.practitionerId = offenderInfo.practitionerId
      existing.firstCheckin = offenderInfo.firstCheckin
      existing.checkinInterval = offenderInfo.checkinInterval.duration
      existing.createdBy = offenderInfo.practitionerId
      existing.updatedAt = now
      existing.contactPreference = offenderInfo.contactPreference
      existing
    } else {
      Offender(
        uuid = UUID.randomUUID(),
        crn = offenderInfo.crn.trim().uppercase(),
        practitionerId = offenderInfo.practitionerId,
        status = OffenderStatus.INITIAL,
        firstCheckin = offenderInfo.firstCheckin,
        checkinInterval = offenderInfo.checkinInterval.duration,
        createdAt = now,
        createdBy = offenderInfo.practitionerId,
        updatedAt = now,
        contactPreference = offenderInfo.contactPreference,
      )
    }

    raiseOnConstraintViolation("CRN ${offenderInfo.crn} already exists") {
      offenderRepository.save(offender)
    }

    val setup = OffenderSetup(
      uuid = offenderInfo.setupUuid,
      offender = offender,
      practitionerId = offenderInfo.practitionerId,
      createdAt = now,
      startedAt = offenderInfo.startedAt,
      eligibilityChoice = offenderInfo.eligibilityChoice,
      rationale = offenderInfo.rationale,
    )

    val saved =
      raiseOnConstraintViolation("Setup with UUID ${offenderInfo.setupUuid} already exists") {
        offenderSetupRepository.save(setup)
      }

    LOGGER.info(
      "Started V2 offender setup: offender={}, crn={}, setup={}",
      offender.uuid,
      offender.crn,
      saved.uuid,
    )

    return saved.dto()
  }

  /**
   * Complete offender setup Verifies photo, changes status to VERIFIED, sends notifications, and
   * creates first checkin if due
   */
  fun completeOffenderSetup(uuid: UUID): OffenderDto {
    val setup = offenderSetupRepository.findByUuid(uuid).getOrElse {
      throw BadArgumentException("No setup for given uuid=$uuid")
    }
    val photoUploaded = s3UploadService.isSetupPhotoUploaded(setup)
    if (!photoUploaded) {
      throw InvalidOffenderSetupState("No uploaded photo for setup uuid=$uuid", setup)
    }

    val offender = setup.offender
    val contactDetails =
      try {
        ndiliusApiClient.getContactDetails(offender.crn)
      } catch (e: Exception) {
        LOGGER.warn("Failed to fetch contact details for CRN={}", offender.crn, e)
        null
      }

    // Don't onboard a POP who is no longer eligible for online check-ins (no active events, or in
    // reset). We only block when NDelius details are available - a transient fetch failure must not
    // prevent setup completion. The daily creation job applies the same check on an ongoing basis.
    if (contactDetails != null) {
      checkinIneligibilityReason(offender, contactDetails)?.let { reason ->
        throw BadArgumentException("Cannot complete setup for CRN ${offender.crn}: ${reason.description}")
      }
    }

    val now = clock.instant()
    offender.status = OffenderStatus.VERIFIED
    offender.updatedAt = now
    val createCheckin = offender.firstCheckin == LocalDate.now(clock) && contactDetails != null
    val result = offenderSetupPersistenceService.completeOffenderSetupAndMaybeCreateCheckin(offender, contactDetails, createCheckin)

    LOGGER.info(
      "Completed V2 offender setup: offender={}, crn={}, setup={}, checkin={}",
      offender.uuid,
      offender.crn,
      uuid,
      result.checkin ?: "not due",
    )
    try {
      notificationService.sendSetupCompletedNotifications(offender, contactDetails, setup.dto())
    } catch (e: Exception) {
      LOGGER.warn("Failed to send setup completed notifications for offender {}", offender.crn, e)
    }

    return offender.dto(contactDetails)
  }

  /**
   * Atomically activate the offender and increment the setup counter.
   * Only increments if the offender is currently INACTIVE, ensuring idempotency
   * against concurrent or retried reactivation requests.
   *
   * @return the offender and setupId, or null setupId if no setup exists
   */
  @Transactional
  fun activateOffenderAndIncrementSetupCounter(offender: Offender): Pair<Offender, UUID?> {
    if (offender.status != OffenderStatus.INACTIVE) {
      val setup = offenderSetupRepository.findByOffender(offender).orElse(null)
      return Pair(offender, setup?.setupId())
    }

    offender.status = OffenderStatus.VERIFIED
    offender.updatedAt = clock.instant()
    val savedOffender = offenderRepository.save(offender)

    val setup = offenderSetupRepository.findByOffender(offender).orElse(null)
    setup?.let {
      it.incrementSetupCounter()
      offenderSetupRepository.save(it)
    }

    return Pair(savedOffender, setup?.setupId())
  }

  /** Terminate offender setup (cancel registration) */
  fun terminateOffenderSetup(uuid: UUID): OffenderDto {
    val setup =
      offenderSetupRepository.findByUuid(uuid).getOrElse {
        throw BadArgumentException("No setup for given uuid=$uuid")
      }

    if (setup.offender.status != OffenderStatus.INITIAL) {
      throw BadArgumentException("Setup already completed or terminated")
    }

    val now = clock.instant()
    val offender = setup.offender

    // Fetch personal details for response - NO transaction
    val contactDetails =
      try {
        ndiliusApiClient.getContactDetails(offender.crn)
      } catch (e: Exception) {
        LOGGER.warn("Failed to fetch contact details for CRN={}", offender.crn, e)
        null
      }

    // Mark offender as INACTIVE - separate transaction
    val saved =
      transactionTemplate.execute {
        offender.status = OffenderStatus.INACTIVE
        offender.updatedAt = now
        val updated = offenderRepository.save(offender)
        offenderSetupRepository.delete(setup)
        updated
      }!!

    LOGGER.info(
      "Terminated V2 setup: setup={}, offender={}, crn={}",
      setup.uuid,
      offender.uuid,
      offender.crn,
    )

    return saved.dto(contactDetails)
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(OffenderSetupService::class.java)
  }
}

/** Exception thrown when setup is in invalid state */
class InvalidOffenderSetupState(message: String, val setup: OffenderSetup) : RuntimeException(message)

/** Helper to raise BadArgumentException on constraint violations */
fun <T> raiseOnConstraintViolation(
  message: String,
  operation: () -> T,
): T {
  try {
    return operation()
  } catch (e: DataIntegrityViolationException) {
    if (e.cause is ConstraintViolationException) {
      throw BadArgumentException(message)
    }
    throw e
  }
}
