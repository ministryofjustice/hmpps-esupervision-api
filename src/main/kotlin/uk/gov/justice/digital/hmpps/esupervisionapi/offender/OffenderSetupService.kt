package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import org.hibernate.exception.ConstraintViolationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.GenericNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.RegistrationConfirmationMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.SingleNotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.saveNotifications
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CreateCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import java.time.Clock
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.jvm.optionals.getOrElse

@Service
class OffenderSetupService(
  private val clock: Clock,
  private val offenderRepository: OffenderRepository,
  private val s3UploadService: S3UploadService,
  private val offenderSetupRepository: OffenderSetupRepository,
  private val offenderCheckinService: OffenderCheckinService,
  private val notificationService: NotificationService,
  private val offenderEventLogRepository: OffenderEventLogRepository,
  private val genericNotificationRepository: GenericNotificationRepository,
) {

  fun findSetupByUuid(uuid: UUID): Optional<OffenderSetup> = offenderSetupRepository.findByUuid(uuid)

  @Transactional
  fun startOffenderSetup(offenderInfo: OffenderInfo): OffenderSetupDto {
    val existingSetup = offenderSetupRepository.findByUuid(offenderInfo.setupUuid)
    if (existingSetup.isPresent) {
      throw BadArgumentException("Setup with UUID ${offenderInfo.setupUuid} already exists")
    }

    // TODO: check practitioner exists!
//    val practitioner = practitionerRepository.findByUuid(offenderInfo.practitionerId)
//      .orElseThrow { BadArgumentException("Practitioner with UUID ${offenderInfo.practitionerId} not found") }

    val now = clock.instant()
    val offender = Offender(
      uuid = UUID.randomUUID(),
      firstName = offenderInfo.firstName.trim(),
      lastName = offenderInfo.lastName.trim(),
      crn = offenderInfo.crn.trim(),
      dateOfBirth = offenderInfo.dateOfBirth,
      email = offenderInfo.email?.lowercase()?.trim(),
      phoneNumber = offenderInfo.phoneNumber?.trim(),
      practitioner = offenderInfo.practitionerId,
      createdAt = now,
      updatedAt = now,
      status = OffenderStatus.INITIAL,
      firstCheckin = offenderInfo.firstCheckinDate,
      checkinInterval = offenderInfo.checkinInterval.duration,
    )

    raiseOnConstraintViolation("contact information already in use") {
      offenderRepository.save(offender)
    }

    val setup = OffenderSetup(
      uuid = offenderInfo.setupUuid,
      offender = offender,
      practitioner = offender.practitioner,
      createdAt = now,
    )

    val saved = raiseOnConstraintViolation("Offender setup with UUID ${offenderInfo.setupUuid} already exists") {
      offenderSetupRepository.save(setup)
    }
    return saved.dto()
  }

  @Transactional
  fun completeOffenderSetup(uuid: UUID): OffenderDto {
    val setup = offenderSetupRepository.findByUuid(uuid)
    if (setup.isEmpty) {
      throw BadArgumentException("No setup for given uuid=$uuid")
    }
    if (!s3UploadService.isSetupPhotoUploaded(setup.get())) {
      throw InvalidOffenderSetupState("No uploaded photo offender for given setup uuid=$uuid", setup.get())
    }

    val now = clock.instant()
    val offender = setup.get().offender
    offender.status = OffenderStatus.VERIFIED
    offender.updatedAt = now
    val saved = offenderRepository.save(offender)

    val logEntry = OffenderEventLog(UUID.randomUUID(), LogEntryType.OFFENDER_SETUP_COMPLETE, "complete", practitioner = offender.practitioner, saved, null, now)
    offenderEventLogRepository.save(logEntry)

    // send registration confirmation message to PoP
    val confirmationMessage = RegistrationConfirmationMessage.fromSetup(setup.get())
    val notifContext = SingleNotificationContext.from(confirmationMessage, clock)
    val notifResult = this.notificationService.sendMessage(confirmationMessage, saved, notifContext)
    LOG.info("Completing offender setup for offender uuid={}, notification-ids={}", saved.uuid, notifResult.results.map { it.notificationId })

    try {
      genericNotificationRepository.saveNotifications(confirmationMessage.messageType, notifContext, offender, notifResult)
    } catch (e: Exception) {
      LOG.warn("Failed to persist registration confirmation notifications for offender={}, reference={}", saved.uuid, notifResult.results.map { it.notificationId }, e)
    }

    val firstCheckinDate = offender.firstCheckin
    LOG.debug("offender={}, firstCheckinDate={}", offender.uuid, firstCheckinDate)
    if (firstCheckinDate !== null && firstCheckinDate <= LocalDate.now(clock)) {
      val creation = offenderCheckinService.createCheckin(
        CreateCheckinRequest(
          practitioner = offender.practitioner,
          offender = saved.uuid,
          dueDate = firstCheckinDate,
        ),
        SingleNotificationContext.forCheckin(firstCheckinDate),
      )
      LOG.info("Created a checkin for new offender={}, checkin={}", offender.uuid, creation.checkin.uuid)
    }

    return saved.dto(this.s3UploadService)
  }

  @Transactional
  fun terminateOffenderSetup(uuid: UUID): OffenderDto {
    val setup = offenderSetupRepository.findByUuid(uuid).getOrElse {
      throw BadArgumentException("No setup for given uuid=$uuid")
    }
    if (setup.offender.status != OffenderStatus.INITIAL) {
      throw BadArgumentException("setup already completed or terminated")
    }

    val now = clock.instant()
    val offender = setup.offender
    offender.status = OffenderStatus.INACTIVE
    offender.phoneNumber = null
    offender.email = null
    offender.updatedAt = now

    val deleted = offenderRepository.save(offender)
    offenderSetupRepository.delete(setup)
    LOG.info("terminated setup={}", setup.uuid)

    return deleted.dto(this.s3UploadService)
  }

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(this::class.java)
  }
}

/**
 * We attempt to perform an update (save) operation and want
 * to surface constraint violations as 4xx errors in to the client.
 * Ideally with a useful error message, but we'd need to parser
 * the constraint name from the exception message, which
 * can look different in different databases.
 */
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
