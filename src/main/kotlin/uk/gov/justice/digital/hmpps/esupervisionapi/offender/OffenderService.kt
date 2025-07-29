package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import jakarta.transaction.Transactional
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.ResourceNotFoundException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.toPagination
import java.time.Duration
import java.util.UUID
import kotlin.jvm.optionals.getOrElse

@Service
class OffenderService(
  private val offenderRepository: OffenderRepository,
  private val practitionerRepository: PractitionerRepository,
  private val s3UploadService: S3UploadService,
  @Value("\${app.upload-ttl-minutes}") val uploadTTlMinutes: Long,
) {

  val uploadTTl = Duration.ofMinutes(uploadTTlMinutes)

  fun getOffenders(practitionerUuid: String, pageable: Pageable): CollectionDto<OffenderDto> {
    val practitioner = practitionerRepository.findByUuid(practitionerUuid).getOrElse {
      throw BadArgumentException("Practitioner not found for practitioner.uuid: $practitionerUuid")
    }
    val page = offenderRepository.findAllByPractitioner(practitioner, pageable)
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
    if (offender.status == OffenderStatus.INACTIVE) {
      throw BadArgumentException("Offender is inactive, cannot update details")
    }
    if (details.email.isNullOrEmpty() && details.phoneNumber.isNullOrEmpty()) {
      throw BadArgumentException("email and phone number cannot both be null or empty")
    }
    if (offender.status == OffenderStatus.VERIFIED && details.firstCheckin == null) {
      throw BadArgumentException("first checkin date required when offender status is VERIFIED")
    }

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

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(this::class.java)
  }
}

enum class DeleteResult {
  DELETED,
  NO_RECORD,
  RECORD_IN_USE,
}
