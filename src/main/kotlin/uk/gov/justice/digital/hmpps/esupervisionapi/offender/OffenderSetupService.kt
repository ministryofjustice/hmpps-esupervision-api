package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import org.hibernate.exception.ConstraintViolationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.RegistrationConfirmationMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import java.time.Clock
import java.util.Optional
import java.util.UUID
import kotlin.jvm.optionals.getOrElse

@Service
class OffenderSetupService(
  private val clock: Clock,
  private val offenderRepository: OffenderRepository,
  private val practitionerRepository: PractitionerRepository,
  private val s3UploadService: S3UploadService,
  private val offenderSetupRepository: OffenderSetupRepository,
  private val notificationService: NotificationService,
) {

  fun findSetupByUuid(uuid: UUID): Optional<OffenderSetup> = offenderSetupRepository.findByUuid(uuid)

  @Transactional
  fun startOffenderSetup(offenderInfo: OffenderInfo): OffenderSetupDto {
    val existingSetup = offenderSetupRepository.findByUuid(offenderInfo.setupUuid)
    if (existingSetup.isPresent) {
      throw BadArgumentException("Setup with UUID ${offenderInfo.setupUuid} already exists")
    }

    val practitioner = practitionerRepository.findByUuid(offenderInfo.practitionerId)
      .orElseThrow { BadArgumentException("Practitioner with UUID ${offenderInfo.practitionerId} not found") }

    val now = clock.instant()
    val offender = Offender(
      uuid = UUID.randomUUID(),
      firstName = offenderInfo.firstName,
      lastName = offenderInfo.lastName,
      dateOfBirth = offenderInfo.dateOfBirth,
      email = offenderInfo.email?.lowercase(),
      phoneNumber = offenderInfo.phoneNumber,
      practitioner = practitioner,
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
      practitioner = practitioner,
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
    offenderRepository.save(offender)

    val saved = offenderRepository.save(offender)

    // send registration confirmation message to PoP
    val confirmationMessage = RegistrationConfirmationMessage.fromSetup(setup.get())
    this.notificationService.sendMessage(confirmationMessage, offender, SingleNotificationContext(UUID.randomUUID()))

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
