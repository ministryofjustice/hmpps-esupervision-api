package uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup

import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
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
  fun startOffenderSetup(offenderInfo: OffenderInfoV2): OffenderSetupV2Dto {
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
   * Complete offender setup Verifies photo, changes status to VERIFIED, sends notifications, and
   * creates first checkin if due
   */
  fun completeOffenderSetup(uuid: UUID): OffenderV2Dto {
    val setup =
      offenderSetupRepository.findByUuid(uuid).getOrElse {
        throw BadArgumentException("No setup for given uuid=$uuid")
      }
    val photoUploaded = s3UploadService.isSetupPhotoUploaded(setup)
    val offender = setup.offender

    val contactDetails =
      try {
        ndiliusApiClient.getContactDetails(offender.crn)
      } catch (e: Exception) {
        LOGGER.warn("Failed to fetch contact details for CRN={}", offender.crn, e)
        null
      }

    if (!photoUploaded) {
      throw InvalidOffenderSetupState("No uploaded photo for setup uuid=$uuid", setup)
    }

    val now = clock.instant()
    val firstCheckinDate = offender.firstCheckin
    val shouldCreateFirstCheckin = firstCheckinDate <= LocalDate.now(clock)

    val (savedOffender, savedCheckin) =
      transactionTemplate.execute {
        offender.status = OffenderStatus.VERIFIED
        offender.updatedAt = now
        val saved = offenderRepository.save(offender)

        val checkin =
          if (shouldCreateFirstCheckin) {
            val newCheckin =
              OffenderCheckinV2(
                uuid = UUID.randomUUID(),
                offender = saved,
                status = CheckinV2Status.CREATED,
                dueDate = firstCheckinDate,
                createdAt = now,
                createdBy = "SYSTEM",
              )
            checkinRepository.save(newCheckin)
          } else {
            null
          }

        Pair(saved, checkin)
      }!!

    LOGGER.info(
      "Completed V2 offender setup: offender={}, crn={}, setup={}",
      savedOffender.uuid,
      savedOffender.crn,
      uuid,
    )

    if (savedCheckin != null) {
      LOGGER.info(
        "Created first checkin for V2 offender={}, checkin={}",
        savedOffender.uuid,
        savedCheckin.uuid,
      )
    }

    notificationService.sendSetupCompletedNotifications(savedOffender, contactDetails)

    if (savedCheckin != null) {
      if (contactDetails != null) {
        notificationService.sendCheckinCreatedNotifications(savedCheckin, contactDetails)
      } else {
        LOGGER.warn(
          "Skipping checkin created notifications for checkin {}: contact details not found",
          savedCheckin.uuid,
        )
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
