package uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup

import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationFailureException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderInfoInitial
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderInfoV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupV2Dto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Dto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.time.Clock
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.jvm.optionals.getOrElse

/**
 * V2 Offender Setup Service Handles registration/setup workflow for V2 offenders
 *
 * Key differences from V1:
 * - No PII storage (only CRN)
 * - Uses V2 entities and repositories
 * - Uses V2 notification and audit services
 */
@Service
class OffenderSetupV2Service(
  private val clock: Clock,
  private val offenderRepository: OffenderV2Repository,
  private val offenderSetupRepository: OffenderSetupV2Repository,
  private val checkinRepository: OffenderCheckinV2Repository,
  private val s3UploadService: S3UploadService,
  private val notificationService: NotificationV2Service,
  private val eventAuditService: EventAuditV2Service,
  private val ndiliusApiClient: INdiliusApiClient,
  private val transactionTemplate: TransactionTemplate,
) {

  fun findSetupByUuid(uuid: UUID): Optional<OffenderSetupV2> = offenderSetupRepository.findByUuid(uuid)

  /** Start offender setup (registration) Creates OffenderV2 and OffenderSetupV2 records */
  @Transactional
  internal fun startOffenderSetup(offenderInfo: OffenderInfoV2): OffenderSetupV2Dto {
    val now = clock.instant()

    // Create V2 offender (no PII, only CRN)
    val offender =
      OffenderV2(
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

    raiseOnConstraintViolation("CRN ${offenderInfo.crn} already exists") {
      offenderRepository.save(offender)
    }

    // Create setup record
    val setup =
      OffenderSetupV2(
        uuid = offenderInfo.setupUuid,
        offender = offender,
        practitionerId = offenderInfo.practitionerId,
        createdAt = now,
        startedAt = offenderInfo.startedAt,
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

    return OffenderSetupV2Dto(
      uuid = saved.uuid,
      practitionerId = saved.practitionerId,
      offenderUuid = saved.offender.uuid,
      createdAt = saved.createdAt,
      startedAt = saved.startedAt,
    )
  }

  /**
   * Initiates or re-starts the setup process for an offender based on the provided offender information.
   * Checks if an offender setup already exists for the given CRN. If no setup exists, a new setup is initiated.
   * If a setup exists but the offender's status is not "INITIAL", an exception is thrown.
   * Otherwise, the existing offender information is updated with the provided details.
   *
   * @param offenderInfo the initial information about the offender
   * @return the updated offender setup
   * @throws BadArgumentException if the offender exists but has a status other than "INITIAL"
   */
  @Transactional
  fun startOffenderSetup(offenderInfo: OffenderInfoInitial): OffenderSetupV2Dto {
    LOGGER.info("Initiating offender setup for CRN={}", offenderInfo.crn)
    val setup = offenderSetupRepository.findByCrn(offenderInfo.crn).orElse(null)
    if (setup == null) {
      return startOffenderSetup(offenderInfo.toOffenderInfoV2())
    } else if (setup.offender.status != OffenderStatus.INITIAL) {
      throw BadArgumentException("Offender already exists.")
    }

    val now = clock.instant()
    val offender = setup.offender
    offender.practitionerId = offenderInfo.practitionerId
    offender.firstCheckin = offenderInfo.firstCheckin
    offender.checkinInterval = offenderInfo.checkinInterval.duration
    offender.contactPreference = offenderInfo.contactPreference
    offender.updatedAt = now

    offenderRepository.save(offender)

    LOGGER.info(
      "Re-started V2 offender setup: offender={}, crn={}, setup={}",
      offender.uuid,
      offender.crn,
      setup.uuid,
    )

    return setup.dto()
  }

  /**
   * Complete offender setup Verifies photo, changes status to VERIFIED, sends notifications, and
   * creates first checkin if due
   */
  @Transactional
  fun completeOffenderSetup(uuid: UUID): OffenderV2Dto {
    val setup =
      offenderSetupRepository.findByUuid(uuid).getOrElse {
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

    val now = clock.instant()
    offender.status = OffenderStatus.VERIFIED
    offender.updatedAt = now
    val savedOffender = offenderRepository.save(offender)

    var checkin: OffenderCheckinV2? = null
    val checkinDueToday = savedOffender.firstCheckin == LocalDate.now(clock)
    if (checkinDueToday) {
      checkin = checkinRepository.save(
        OffenderCheckinV2(
          uuid = UUID.randomUUID(),
          offender = savedOffender,
          status = CheckinV2Status.CREATED,
          dueDate = savedOffender.firstCheckin,
          createdAt = now,
          createdBy = "SYSTEM",
        ),
      )
    }

    LOGGER.info(
      "Completed V2 offender setup: offender={}, crn={}, setup={}, checkin={}",
      savedOffender.uuid,
      savedOffender.crn,
      uuid,
      checkin?.uuid ?: "not due",
    )
    notificationService.sendSetupCompletedNotifications(savedOffender, contactDetails)

    checkin?.let {
      try {
        contactDetails?.let { notificationService.sendCheckinCreatedNotifications(checkin, contactDetails) }
      } catch (e: NotificationFailureException) {
        LOGGER.info("Notification failure {}", e.message, e)
      } catch (e: Exception) {
        LOGGER.warn("Unknown notification failure {}", checkin.uuid, e)
      }
    }

    return savedOffender.dto(contactDetails)
  }

  /** Terminate offender setup (cancel registration) */
  fun terminateOffenderSetup(uuid: UUID): OffenderV2Dto {
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
    private val LOGGER = LoggerFactory.getLogger(OffenderSetupV2Service::class.java)
  }
}

/** Exception thrown when setup is in invalid state */
class InvalidOffenderSetupState(message: String, val setup: OffenderSetupV2) : RuntimeException(message)

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

private fun OffenderInfoInitial.toOffenderInfoV2(): OffenderInfoV2 = OffenderInfoV2(
  setupUuid = UUID.randomUUID(),
  practitionerId = practitionerId,
  crn = crn,
  firstCheckin = firstCheckin,
  checkinInterval = checkinInterval,
  contactPreference = contactPreference,
  startedAt = startedAt,
)
