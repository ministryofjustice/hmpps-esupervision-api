package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CreateCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.ResourceNotFoundException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.toPagination
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Optional
import java.util.UUID

class MissingVideoException(message: String, val checkin: OffenderCheckin) : RuntimeException(message)
class InvalidStateTransitionException(message: String, val checkin: OffenderCheckin) : RuntimeException(message)

@Service
class OffenderCheckinService(
  private val checkinRepository: OffenderCheckinRepository,
  private val offenderRepository: OffenderRepository,
  private val practitionerRepository: PractitionerRepository,
  private val s3UploadService: S3UploadService,
) {

  fun getCheckin(uuid: UUID): Optional<OffenderCheckinDto> {
    val checkin = checkinRepository.findByUuid(uuid)
    return checkin.map { it.dto() }
  }

  fun createCheckin(createCheckin: CreateCheckinRequest): OffenderCheckinDto {
    val now = Instant.now()
    val reqDueDate = Instant.from(createCheckin.dueDate.atStartOfDay(ZoneId.of("UTC")))
    if (reqDueDate <= now) {
      throw BadArgumentException("Due date is in the past: ${createCheckin.dueDate}")
    }

    val offenderDto = offenderRepository.findByUuid(createCheckin.offender)
    val practitionerDto = practitionerRepository.findByUuid(createCheckin.practitioner)

    if (offenderDto.isEmpty) {
      throw ResourceNotFoundException("Offender with uuid=${createCheckin.offender} not found")
    }
    if (practitionerDto.isEmpty) {
      throw ResourceNotFoundException("Practitioner with uuid=${createCheckin.practitioner} not found")
    }

    val offender = offenderDto.get()
    val practitioner = practitionerDto.get()
    val checkin = OffenderCheckin(
      uuid = UUID.randomUUID(),
      createdBy = practitioner,
      createdAt = now,
      offender = offender,
      submittedAt = null,
      reviewedBy = null,
      status = CheckinStatus.CREATED,
      questions = createCheckin.questions,
      answers = null,
      dueDate = reqDueDate,
      autoIdCheck = null,
      manualIdCheck = null,
    )

    val saved = checkinRepository.save(checkin)
    return saved.dto()
  }

  /**
   * Submits a checkin on behalf of the offender.
   *
   * @throws InvalidStateTransitionException when
   * @throws RuntimeException when no offender or checkin with given uuid exist, or vid
   * @throws MissingVideoException when no video has been uploaded
   */
  fun submitCheckin(checkinUuid: UUID, checkinInput: OffenderCheckinSubmission): OffenderCheckinDto {
    val offenderEntity = offenderRepository.findByUuid(checkinInput.offender)
    val checkinEntity = checkinRepository.findByUuid(checkinUuid)

    if (offenderEntity.isEmpty) {
      throw ResourceNotFoundException("Offender with uuid=${checkinInput.offender} not found")
    }
    if (checkinEntity.isEmpty) {
      throw ResourceNotFoundException("Checkin with uuid=${checkinInput.offender} not found")
    }

    val now = Instant.now()

    val checkin = checkinEntity.get()
    if (checkin.dueDate < now) {
      throw InvalidStateTransitionException("Checkin past due date", checkin)
    }
    if (checkin.status != CheckinStatus.CREATED) {
      throw InvalidStateTransitionException("Can't submit checkin with status=${checkin.status}", checkin)
    }

    // NOTE(rosado): there's no automated id verification at the moment, so we only check
    // if a video was uploaded. Once automated checkin is in, we can replace this
    // with a check of the `autoIdCheck` property (where null means no check was performed
    // due to missing video)
    val videoUploaded = s3UploadService.isCheckinVideoUploaded(checkin)
    if (!videoUploaded) {
      throw MissingVideoException("Cannot submit a checkin without a video, checkin ${checkin.uuid}", checkin)
    }

    checkin.submittedAt = now
    checkin.answers = checkinInput.answers
    checkin.status = CheckinStatus.SUBMITTED

    checkinRepository.save(checkin)
    return checkin.dto()
  }

  fun generateVideoUploadLocation(checkinUuid: UUID, contentType: String, duration: Duration): URL {
    val checkin = checkinRepository.findByUuid(checkinUuid)
    if (checkin.isPresent) {
      return s3UploadService.generatePresignedUploadUrl(checkin.get(), contentType, duration)
    }

    throw ResourceNotFoundException("checkin not found")
  }

  fun getCheckins(pageRequest: PageRequest): CollectionDto<OffenderCheckinDto> {
    val page = checkinRepository.findAll(pageRequest)
    val checkins = page.content.map { it.dto() }
    return CollectionDto(page.pageable.toPagination(), checkins)
  }
}
