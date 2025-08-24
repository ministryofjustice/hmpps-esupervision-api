package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import jakarta.transaction.Transactional
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.NewPractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.ResourceNotFoundException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.toPagination
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.jvm.optionals.getOrElse

@Service
class OffenderService(
  private val clock: Clock,
  private val offenderRepository: OffenderRepository,
  private val checkinService: OffenderCheckinService,
  private val offenderEventLogRepository: OffenderEventLogRepository,
  private val s3UploadService: S3UploadService,
  @Value("\${app.upload-ttl-minutes}") val uploadTTlMinutes: Long,
  private val newPractitionerRepository: NewPractitionerRepository,
) {

  val uploadTTl = Duration.ofMinutes(uploadTTlMinutes)

  fun getOffenders(practitionerId: ExternalUserId, pageable: Pageable): CollectionDto<OffenderDto> {
    // TODO: check practitioner exists?
    val page = offenderRepository.findAllByPractitioner(practitionerId, pageable)
    val offenders = page.content.map { it.dto(this.s3UploadService) }
    return CollectionDto(page.pageable.toPagination(), offenders)
  }

  fun deleteOffender(uuid: UUID): DeleteResult {
    LOG.info("Attempting to delete offender: $uuid")
    val offenderFound = offenderRepository.findByUuid(uuid)
    var result = DeleteResult.NO_RECORD
    if (offenderFound.isPresent) {
      val offender = offenderFound.get()
      if (offender.status == OffenderStatus.VERIFIED) {
        return DeleteResult.RECORD_IN_USE
      }
      offenderRepository.delete(offender)
      result = DeleteResult.DELETED
    }
    return result
  }

  fun getOffender(uuid: UUID): OffenderDto {
    val offenderFound = offenderRepository.findByUuid(uuid)
    return offenderFound.map { it.dto(this.s3UploadService) }.getOrElse {
      throw ResourceNotFoundException("Offender not found for uuid: $uuid")
    }
  }

  @Transactional
  fun updateDetails(uuid: UUID, details: OffenderDetailsUpdate): OffenderDto {
    val offender = offenderRepository.findByUuid(uuid).getOrElse {
      throw ResourceNotFoundException("Offender not found for uuid: $uuid")
    }
    details.validate(offender, clock)

    LOG.info("Updating offender={} details with {}", uuid, details)
    offender.applyUpdate(details)
    return offenderRepository.save(offender).dto(
      this.s3UploadService,
    )
  }

  fun photoUploadLocation(uuid: UUID, contentType: String): LocationInfo {
    val offender = offenderRepository.findByUuid(uuid).getOrElse {
      throw ResourceNotFoundException("Offender not found for uuid: $uuid")
    }
    if (offender.status == OffenderStatus.INACTIVE) {
      throw BadArgumentException("Offender is inactive, cannot update details")
    }

    return LocationInfo(
      url = s3UploadService.generatePresignedUploadUrl(offender, contentType, uploadTTl),
      contentType = contentType,
      uploadTTl.toString(),
    )
  }

  /**
   * Call when the offender will no longer need to use the platform (e.g., probation ended,
   * or the practitioner decided it's not working out).
   *
   * The method will attempt to update the offender's record and clean up any related data.
   *
   * @param uuid the offender's UUID
   */
  @Transactional
  fun cancelCheckins(uuid: UUID, body: DeactivateOffenderCheckinRequest): OffenderDto {
    val offender = offenderRepository.findByUuid(uuid).getOrElse {
      throw ResourceNotFoundException("Offender not found for uuid: $uuid")
    }

    // check practitioner exists
    newPractitionerRepository.expectById(body.requestedBy)

    if (!offender.canTransitionTo(OffenderStatus.INACTIVE)) {
      throw BadArgumentException("Offender is inactive, cannot update details")
    }

    offender.deactivate(clock.instant())
    offenderRepository.save(offender)
    LOG.info("practitioner={} deactivated offender={}", body.requestedBy, offender.uuid)

    val logEntry = OffenderEventLog(
      UUID.randomUUID(),
      LogEntryType.OFFENDER_DEACTIVATED,
      body.reason,
      body.requestedBy,
      offender,
    )
    offenderEventLogRepository.saveAndFlush(logEntry)

    checkinService.cancelCheckins(uuid, logEntry)

    return offender.dto(s3UploadService)
  }

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(this::class.java)
  }
}

private fun Offender.deactivate(terminationTime: Instant) {
  this.status = OffenderStatus.INACTIVE
  this.phoneNumber = null
  this.email = null
  this.firstCheckin = null
  this.updatedAt = terminationTime
}

enum class DeleteResult {
  DELETED,
  NO_RECORD,
  RECORD_IN_USE,
}

internal fun OffenderDetailsUpdate.validate(
  offender: Offender,
  clock: Clock,
) {
  if (offender.status == OffenderStatus.INACTIVE) {
    throw BadArgumentException("Offender is inactive, cannot update details")
  }
  if (this.email.isNullOrEmpty() && this.phoneNumber.isNullOrEmpty()) {
    throw BadArgumentException("email and phone number cannot both be null or empty")
  }
  if (offender.status == OffenderStatus.VERIFIED && this.firstCheckin == null) {
    throw BadArgumentException("first checkin date required when offender status is VERIFIED")
  }
  val now = clock.instant()
  val today = now.atZone(clock.zone).toLocalDate()
  if (this.dateOfBirth != null && this.dateOfBirth.isAfter(today.minusYears(14))) {
    throw BadArgumentException("Invalid date of birth: ${this.dateOfBirth}")
  }
}
