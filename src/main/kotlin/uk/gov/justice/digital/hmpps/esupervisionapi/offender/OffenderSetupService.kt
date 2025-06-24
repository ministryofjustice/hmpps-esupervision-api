package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Service
class OffenderSetupService(
  private val offenderRepository: OffenderRepository,
  private val practitionerRepository: PractitionerRepository,
  private val s3UploadService: S3UploadService,
  private val notificationService: NotificationService,
  private val offenderSetupRepository: OffenderSetupRepository,
) {

  fun findSetupByUuid(uuid: UUID): Optional<OffenderSetup> = offenderSetupRepository.findByUuid(uuid)

  fun startOffenderSetup(offenderInfo: OffenderInfo): OffenderSetupDto {
    val existingSetup = offenderSetupRepository.findByUuid(offenderInfo.setupUuid)
    if (existingSetup.isPresent) {
      throw BadArgumentException("Setup with UUID ${offenderInfo.setupUuid} already exists")
    }

    val practitionerOption = practitionerRepository.findByUuid(offenderInfo.practitionerId)
    if (practitionerOption.isEmpty) {
      throw BadArgumentException("Practitioner with UUID ${offenderInfo.practitionerId} not found")
    }

    val practitioner = practitionerOption.get()

    // TODO: add proper validation here
    val phoneNumUtil = PhoneNumberUtil.getInstance()
    var validationFailures = mutableListOf<Pair<OffenderInfo, String>>()
    var validatedInfo = offenderInfo
    if (offenderInfo.firstName.length <= 2) {
      validationFailures.add(Pair(offenderInfo, "Invalid first name"))
    } else if (offenderInfo.lastName.length <= 2) {
      validationFailures.add(Pair(offenderInfo, "Invalid last name"))
    } else if (offenderInfo.phoneNumber != null) {
      try {
        val number = phoneNumUtil.parse(offenderInfo.phoneNumber, "GB")
        if (phoneNumUtil.isValidNumber(number)) {
        } else {
          validationFailures.add(Pair(offenderInfo, "Invalid phone number"))
        }
      } catch (e: NumberParseException) {
        validationFailures.add(Pair(offenderInfo, "Invalid phone number"))
      }
    } else if (offenderInfo.email != null && !offenderInfo.email.contains("@")) {
      validationFailures.add(Pair(offenderInfo, "Invalid email address"))
    }
    if (validationFailures.isNotEmpty()) {
      throw BadArgumentException(validationFailures.joinToString("\n"))
    }

    val now = Instant.now()
    val offender = Offender(
      uuid = UUID.randomUUID(),
      firstName = validatedInfo.firstName,
      lastName = validatedInfo.lastName,
      dateOfBirth = validatedInfo.dateOfBirth,
      email = validatedInfo.email,
      phoneNumber = validatedInfo.phoneNumber,
      practitioner = practitioner,
      createdAt = now,
      updatedAt = now,
      status = OffenderStatus.INITIAL,
    )
    offenderRepository.save(offender)
    val setup = OffenderSetup(
      uuid = offenderInfo.setupUuid,
      offender = offender,
      practitioner = practitioner,
      createdAt = now,
    )
    offenderSetupRepository.save(setup)

    return setup.dto()
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

    val now = Instant.now()
    val offender = setup.get().offender
    offender.status = OffenderStatus.VERIFIED
    offender.updatedAt = now
    offenderRepository.save(offender)

    val saved = offenderRepository.save(offender)
    return saved.dto()
  }

  @Transactional
  fun terminateOffenderSetup(uuid: UUID): OffenderDto {
    val setup = offenderSetupRepository.findByUuid(uuid)
    if (setup.isEmpty) {
      throw BadArgumentException("No setup for given uuid=$uuid")
    }
    if (setup.get().offender.status != OffenderStatus.INITIAL) {
      throw BadArgumentException("setup already completed or terminated")
    }

    val now = Instant.now()
    val offender = setup.get().offender
    offender.status = OffenderStatus.INACTIVE
    offender.phoneNumber = null
    offender.email = null
    offender.updatedAt = now

    val deleted = offenderRepository.save(offender)
    offenderSetupRepository.delete(setup.get())

    return deleted.dto()
  }

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(this::class.java)
  }
}

// fun OffenderInvite.toOffenderInfo(): OffenderInfo = OffenderInfo(firstName, lastName, dateOfBirth, email, phoneNumber)
