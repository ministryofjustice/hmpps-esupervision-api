package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.web.servlet.resource.NoResourceFoundException
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.OffenderCheckinInviteMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PractitionerCheckinSubmittedMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinReviewRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CreateCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.toPagination
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Optional
import java.util.UUID
import kotlin.jvm.optionals.getOrElse

class MissingVideoException(message: String, val checkin: OffenderCheckin) : RuntimeException(message)
class InvalidStateTransitionException(message: String, val checkin: OffenderCheckin) : RuntimeException(message)
class InvalidOffenderSetupState(message: String, setup: OffenderSetup) : RuntimeException(message)

@Service
class OffenderCheckinService(
  private val checkinRepository: OffenderCheckinRepository,
  private val offenderRepository: OffenderRepository,
  private val practitionerRepository: PractitionerRepository,
  private val s3UploadService: S3UploadService,
  private val notificationService: NotificationService,
  @Qualifier("rekognitionS3") private val rekogS3UploadService: S3UploadService,
) {

  fun getCheckin(uuid: UUID): Optional<OffenderCheckinDto> {
    val checkin = checkinRepository.findByUuid(uuid)
    return checkin.map { it.dto(this.s3UploadService) }
  }

  fun createCheckin(createCheckin: CreateCheckinRequest): OffenderCheckinDto {
    val now = Instant.now()
    val reqDueDate = Instant.from(createCheckin.dueDate.atStartOfDay(ZoneId.of("UTC")))
    if (reqDueDate < now) {
      throw BadArgumentException("Due date is in the past: ${createCheckin.dueDate}")
    }

    val offender = offenderRepository.findByUuid(createCheckin.offender).getOrElse {
      throw BadArgumentException("Offender ${createCheckin.offender} not found")
    }
    val practitioner = practitionerRepository.findByUuid(createCheckin.practitioner).getOrElse {
      throw BadArgumentException("Practitioner ${createCheckin.offender} not found")
    }

    if (offender.status != OffenderStatus.VERIFIED) {
      throw BadArgumentException("Offender with uuid=${createCheckin.offender} has status ${offender.status}")
    }

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

    // notify PoP of checkin invite
    val inviteMessage = OffenderCheckinInviteMessage.fromCheckin(checkin)
    this.notificationService.sendMessage(inviteMessage, checkin.offender)

    return saved.dto(this.s3UploadService)
  }

  /**
   * Submits a checkin on behalf of the offender.
   *
   * @throws InvalidStateTransitionException when
   * @throws RuntimeException when no offender or checkin with given uuid exist, or vid
   * @throws MissingVideoException when no video has been uploaded
   */
  fun submitCheckin(checkinUuid: UUID, checkinInput: OffenderCheckinSubmission): OffenderCheckinDto {
    val offender = offenderRepository.findByUuid(checkinInput.offender).getOrElse {
      throw BadArgumentException("Offender ${checkinInput.offender} not found")
    }
    val checkin = checkinRepository.findByUuid(checkinUuid).getOrElse {
      throw NoResourceFoundException(HttpMethod.POST, "/offender_checkins/$checkinUuid")
    }

    if (offender.status != OffenderStatus.VERIFIED) {
      throw BadArgumentException("Offender with uuid=${checkin.uuid} has status ${offender.status}")
    }

    val now = Instant.now()

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

    // notify practitioner that checkin was submitted
    val submissionMessage = PractitionerCheckinSubmittedMessage.fromCheckin(checkin)
    this.notificationService.sendMessage(submissionMessage, checkin.createdBy)

    return checkin.dto(this.s3UploadService)
  }

  fun generateVideoUploadLocation(checkinUuid: UUID, contentType: String, duration: Duration): URL {
    val checkin = checkinRepository.findByUuid(checkinUuid)
    if (checkin.isPresent) {
      validateCheckinUpdatable(checkin.get())
      return s3UploadService.generatePresignedUploadUrl(checkin.get(), contentType, duration)
    }

    throw NoResourceFoundException(HttpMethod.GET, "/offender_checkins/$checkinUuid")
  }

  fun generatePhotoSnapshotLocations(checkinUuid: UUID, contentType: String, number: Long, duration: Duration): List<URL> {
    val checkin = checkinRepository.findByUuid(checkinUuid)
    if (checkin.isPresent) {
      validateCheckinUpdatable(checkin.get())
      val urls = mutableListOf<URL>()
      for (index in 0..<number) {
        val rekogUrl = rekogS3UploadService.generatePresignedUploadUrl(checkin.get(), contentType, index, duration)
        urls.add(rekogUrl)
      }
      return urls
    }

    throw NoResourceFoundException(HttpMethod.POST, "/offender_checkins/$checkinUuid")
  }

  fun getCheckins(practitionerUuid: String, pageRequest: PageRequest): CollectionDto<OffenderCheckinDto> {
    val page = checkinRepository.findAllByCreatedByUuid(practitionerUuid, pageRequest)
    val checkins = page.content.map { it.dto(this.s3UploadService) }
    return CollectionDto(page.pageable.toPagination(), checkins)
  }

  fun setAutomatedIdCheckStatus(checkinUuid: UUID, result: AutomatedIdVerificationResult): OffenderCheckinDto {
    val checkin = checkinRepository.findByUuid(checkinUuid)
    if (checkin.isPresent) {
      LOG.info("updating checking with automated id check result: {}, checkin={}", result, checkinUuid)
      val checkin = checkin.get()
      checkin.autoIdCheck = result
      val saved = checkinRepository.save(checkin)
      return saved.dto(this.s3UploadService)
    }

    throw NoResourceFoundException(HttpMethod.POST, "/offender_checkins/$checkinUuid")
  }

  fun reviewCheckin(checkinUuid: UUID, reviewRequest: CheckinReviewRequest): OffenderCheckinDto {
    val practitioner = practitionerRepository.findByUuid(reviewRequest.practitioner)
      .getOrElse { throw BadArgumentException("practitioner not found") }
    val checkin = checkinRepository.findByUuid(checkinUuid)
      .getOrElse { throw NoResourceFoundException(HttpMethod.GET, "/offender_checkins/$checkinUuid") }

    checkin.reviewedBy = practitioner
    checkin.manualIdCheck = checkin.manualIdCheck
    checkin.status = CheckinStatus.REVIEWED

    return checkinRepository.save(checkin).dto(this.s3UploadService)
  }

  companion object {
    // checkin has been SUBMITTED so we no longer allow to overwrite the associated files
    private fun validateCheckinUpdatable(checkin: OffenderCheckin) {
      if (checkin.status != CheckinStatus.CREATED) {
        throw BadArgumentException("You can no longer add photos/videos to checkin with uuid=${checkin.uuid}")
      }
    }

    private val LOG = LoggerFactory.getLogger(this::class.java)
  }
}
