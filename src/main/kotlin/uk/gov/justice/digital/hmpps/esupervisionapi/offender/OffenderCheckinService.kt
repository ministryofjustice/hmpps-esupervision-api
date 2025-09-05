package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException
import uk.gov.justice.digital.hmpps.esupervisionapi.events.CheckinAdditionalInformation
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.events.HmppsDomainEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.OffenderCheckinInviteMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.OffenderCheckinSubmittedMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PractitionerCheckinSubmittedMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.CheckinVerificationImages
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.RekognitionCompareFacesService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinReviewRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinUploadLocationResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CreateCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.toPagination
import java.net.URI
import java.net.URL
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.Period
import java.util.UUID
import kotlin.jvm.optionals.getOrElse

class MissingVideoException(message: String, val checkin: OffenderCheckin) : RuntimeException(message)
class InvalidStateTransitionException(message: String, val checkin: OffenderCheckin) : RuntimeException(message)
class InvalidOffenderSetupState(message: String, setup: OffenderSetup) : RuntimeException(message)

data class UploadLocationTypes(
  val reference: String,
  val video: String,
  val snapshots: List<String>,
)

data class CheckinCreationInfo(
  val checkin: OffenderCheckinDto,
  val notifications: NotificationResults,
)

@Service
class OffenderCheckinService(
  private val clock: Clock,
  private val checkinRepository: OffenderCheckinRepository,
  private val offenderRepository: OffenderRepository,
  private val s3UploadService: S3UploadService,
  private val notificationService: NotificationService,
  @Qualifier("rekognitionS3") private val rekogS3UploadService: S3UploadService,
  private val compareFacesService: RekognitionCompareFacesService,
  @Value("\${app.scheduling.checkin-notification.window:72h}") val checkinWindow: Duration,
  @Value("\${rekognition.face-similarity.threshold}") val faceSimilarityThreshold: Float,
  private val offenderEventLogRepository: OffenderEventLogRepository,
  private val practitionerRepository: PractitionerRepository,
  private val eventPublisher: DomainEventPublisher,
  private val objectMapper: ObjectMapper,
) {

  private val checkinWindowPeriod = Period.ofDays(checkinWindow.toDays().toInt())

  /**
   * @param uuid UUID of the checkin
   * @param includeUploads set to true to ensure vide/snapshot URLs are returned
   */
  fun getCheckin(
    uuid: UUID,
    includeUploads: Boolean = false,
  ): OffenderCheckinResponse {
    val checkin = checkinRepository.findByUuid(uuid).getOrElse {
      throw NoResourceFoundException(HttpMethod.GET, "/offender_checkins/$uuid")
    }
    val logs = offenderEventLogRepository.findAllCheckinEntries(
      checkin,
      setOf(LogEntryType.OFFENDER_CHECKIN_NOT_SUBMITTED),
      PageRequest.of(0, 1),
    )

    var dto = checkin.dto(this.s3UploadService)
    if (includeUploads) {
      dto = if (dto.videoUrl != null) {
        dto
      } else {
        dto.copy(
          videoUrl = s3UploadService.getCheckinVideo(checkin, true),
          snapshotUrl = s3UploadService.getCheckinSnapshot(checkin, true),
        )
      }
    }
    return OffenderCheckinResponse(
      dto,
      OffenderCheckinLogs(
        OffenderCheckinLogsHint.SUBSET,
        logs.content,
      ),
    )
  }

  fun createCheckin(createCheckin: CreateCheckinRequest, notificationContext: NotificationContext): CheckinCreationInfo {
    val now = clock.instant()
    // we want to allow the due date of 'today'
    if (createCheckin.dueDate < now.atZone(clock.zone).toLocalDate()) {
      throw BadArgumentException("Due date is in the past: ${createCheckin.dueDate}")
    }

    val offender = offenderRepository.findByUuid(createCheckin.offender).getOrElse {
      throw BadArgumentException("Offender ${createCheckin.offender} not found")
    }

    val practitioner = practitionerRepository.expectById(createCheckin.practitioner)

    if (offender.status != OffenderStatus.VERIFIED) {
      throw BadArgumentException("Offender with uuid=${createCheckin.offender} has status ${offender.status}")
    }

    val checkin = OffenderCheckin(
      uuid = UUID.randomUUID(),
      createdBy = practitioner.externalUserId(),
      createdAt = now,
      offender = offender,
      submittedAt = null,
      reviewedBy = null,
      reviewedAt = null,
      status = CheckinStatus.CREATED,
      surveyResponse = null,
      dueDate = createCheckin.dueDate,
      autoIdCheck = null,
      manualIdCheck = null,
    )

    val saved = checkinRepository.save(checkin)

    // notify PoP of checkin invite
    val inviteMessage = OffenderCheckinInviteMessage.fromCheckin(checkin, checkinWindowPeriod)
    val inviteResults = this.notificationService.sendMessage(inviteMessage, checkin.offender, notificationContext)

    return CheckinCreationInfo(
      saved.dto(this.s3UploadService),
      inviteResults,
    )
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
    if (!checkin.status.canTransitionTo(CheckinStatus.SUBMITTED)) {
      throw InvalidStateTransitionException("Cannot submit checkin with status=${checkin.status}", checkin)
    }
    if (checkin.isPastSubmissionDate(clock, checkinWindowPeriod)) {
      throw BadArgumentException("Checkin submission past due date")
    }
    validateCheckinUpdatable(checkin)

    val videoUploaded = s3UploadService.isCheckinVideoUploaded(checkin)
    if (!videoUploaded) {
      throw MissingVideoException("Cannot submit a checkin without a video, checkin ${checkin.uuid}", checkin)
    }

    val now = clock.instant()
    checkin.submittedAt = now
    checkin.surveyResponse = checkinInput.survey
    checkin.status = CheckinStatus.SUBMITTED

    checkinRepository.save(checkin)

    // notify practitioner that checkin was submitted
    val practitioner = practitionerRepository.expectById(checkin.createdBy)

    val practitionerConfirmation = PractitionerCheckinSubmittedMessage.fromCheckin(checkin, practitioner)
    this.notificationService.sendMessage(practitionerConfirmation, practitioner, SingleNotificationContext.from(UUID.randomUUID()))

    // notify PoP that checkin was received
    val offenderConfirmation = OffenderCheckinSubmittedMessage.fromCheckin(checkin)
    this.notificationService.sendMessage(offenderConfirmation, checkin.offender, SingleNotificationContext.from(UUID.randomUUID()))

    val domainEvent = HmppsDomainEvent(
      DomainEventType.CHECKIN_RECEIVED.type,
      1,
      null,
      now.atZone(clock.zone),
      DomainEventType.CHECKIN_RECEIVED.description,
      CheckinAdditionalInformation(URI("https://example.com/event").toURL()),
      null,
    )
    eventPublisher.publish(domainEvent)
    return checkin.dto(this.s3UploadService)
  }

  fun generateVideoUploadLocation(checkin: OffenderCheckin, contentType: String, duration: Duration): URL {
    validateCheckinUpdatable(checkin)
    return s3UploadService.generatePresignedUploadUrl(checkin, contentType, duration)
  }

  fun generateUploadLocations(checkin: UUID, types: UploadLocationTypes, duration: Duration): CheckinUploadLocationResponse {
    val checkin = checkinRepository.findByUuid(checkin).getOrElse {
      throw BadArgumentException("Checkin $checkin not found")
    }
    return generateUploadLocations(checkin, types, duration)
  }

  fun getCheckinVerificationImages(checkin: OffenderCheckin, numSnapshots: Int): CheckinVerificationImages {
    // reference image is at index 0
    val reference = rekogS3UploadService.checkinObjectCoordinate(checkin, 0)

    // snapshot locations are at index 1 to numSnapshots+1
    val snapshots = (1..numSnapshots).map {
      rekogS3UploadService.checkinObjectCoordinate(checkin, it)
    }

    return CheckinVerificationImages(reference, snapshots)
  }

  fun generateUploadLocations(checkin: OffenderCheckin, types: UploadLocationTypes, duration: Duration): CheckinUploadLocationResponse {
    validateCheckinUpdatable(checkin)
    if (!types.reference.startsWith("image")) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reference content type: ${types.reference}")
    }
    if (checkin.isPastSubmissionDate(clock, checkinWindowPeriod)) {
      throw BadArgumentException("Checkin submission past due date")
    }

    val referenceUrl = generatePhotoSnapshotUploadLocations(checkin, types.reference, 0..0, duration)
    val videoUrl = generateVideoUploadLocation(checkin, types.video, duration)
    val snapshotUrls = types.snapshots.flatMapIndexed snapshot@{ index, contentType ->
      if (contentType.startsWith("image")) {
        // offsetting by 1; index = 0 is reserved for the reference image
        return@snapshot generatePhotoSnapshotUploadLocations(checkin, contentType, index + 1..index + 1, duration)
      }
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid snapshot contentType: $contentType")
    }

    val durationStr = duration.toString()
    val body = CheckinUploadLocationResponse(
      references = listOf(LocationInfo(referenceUrl[0], types.reference, durationStr)),
      snapshots = if (types.snapshots.isNotEmpty()) snapshotUrls.mapIndexed { index, url -> LocationInfo(url, types.snapshots[index], durationStr) } else null,
      video = LocationInfo(videoUrl, types.video, durationStr),
    )
    return body
  }

  private fun generatePhotoSnapshotUploadLocations(checkin: OffenderCheckin, contentType: String, indices: IntRange, duration: Duration): List<URL> {
    validateCheckinUpdatable(checkin)
    val urls = mutableListOf<URL>()
    for (index in indices) {
      val rekogUrl = rekogS3UploadService.generatePresignedUploadUrl(checkin, contentType, index, duration)
      urls.add(rekogUrl)
    }
    return urls
  }

  fun getCheckins(practitionerId: ExternalUserId, pageRequest: PageRequest): CollectionDto<OffenderCheckinDto> {
    val page = checkinRepository.findAllByCreatedBy(practitionerId, pageRequest)
    val checkins = page.content.map { it.dto(this.s3UploadService) }
    return CollectionDto(page.pageable.toPagination(), checkins)
  }

  @Transactional
  fun reviewCheckin(checkinUuid: UUID, reviewRequest: CheckinReviewRequest): OffenderCheckinDto {
    // check practitioner exists
    practitionerRepository.expectById(reviewRequest.practitioner)

    val checkin = checkinRepository.findByUuid(checkinUuid)
      .getOrElse { throw NoResourceFoundException(HttpMethod.GET, "/offender_checkins/$checkinUuid") }

    if (checkin.status == CheckinStatus.EXPIRED) {
      val missedCheckinComment = reviewRequest.missedCheckinComment?.trim()
      if (checkin.status == CheckinStatus.EXPIRED && missedCheckinComment.isNullOrBlank()) {
        throw BadArgumentException("Reason for missed checkin not given")
      }

      if (missedCheckinComment != null) {
        offenderEventLogRepository.save(
          OffenderEventLog(
            UUID.randomUUID(),
            LogEntryType.OFFENDER_CHECKIN_NOT_SUBMITTED,
            comment = missedCheckinComment,
            practitioner = checkin.createdBy,
            offender = checkin.offender,
            checkin = checkin,
          ),
        )
      }
    } else if (!checkin.status.canTransitionTo(CheckinStatus.REVIEWED)) {
      throw InvalidStateTransitionException("Cannot review checkin with status=${checkin.status}", checkin)
    } else {
      checkin.status = CheckinStatus.REVIEWED
    }

    checkin.reviewedBy = reviewRequest.practitioner
    checkin.manualIdCheck = reviewRequest.manualIdCheck
    checkin.reviewedAt = clock.instant()

    return checkinRepository.save(checkin).dto(this.s3UploadService)
  }

  /**
   * This would be called to trigger a checkin notification (for the offender) outside
   * the periodically executing job. For example an offender was just added
   * and first checkin is supposed to happen today (likely after the job already finished).
   *
   * We require the checkin record as that's what the notification is about and
   * that's where we store notification references which will allow us to
   * retrieve the notification status from the provider (e.g. GOV.UK Notify)
   */
  fun unscheduledNotification(checkinUuid: UUID): OffenderCheckinDto {
    val checkin = checkinRepository.findByUuid(checkinUuid).getOrElse {
      throw BadArgumentException("Checkin=$checkinUuid not found")
    }
    if (checkin.offender.status == OffenderStatus.VERIFIED) {
      throw BadArgumentException("Unscheduled notification for offender of status ${checkin.offender.status}")
    }
    validateCheckinUpdatable(checkin)
    validateUnscheduledNotification(clock.instant(), checkin)

    val inviteMessage = OffenderCheckinInviteMessage.fromCheckin(checkin, checkinWindowPeriod)
    this.notificationService.sendMessage(
      inviteMessage,
      checkin.offender,
      SingleNotificationContext.forCheckin(clock),
    )

    val saved = checkinRepository.save(checkin)
    return saved.dto(this.s3UploadService)
  }

  fun cancelCheckins(offenderUuid: UUID, logEntry: OffenderEventLog) {
    val offender = offenderRepository.findByUuid(offenderUuid).getOrElse {
      throw BadArgumentException("Offender $offenderUuid not found")
    }
    val result = checkinRepository.updateStatusToCancelled(offender)
    LOG.info("Cancelling checkins for offender={}, result={}, logEntry={}", offenderUuid, result, logEntry.uuid)
  }

  fun verifyCheckinIdentity(checkinUuid: UUID, numSnapshots: Int): AutomatedIdVerificationResult {
    val checkin = checkinRepository.findByUuid(checkinUuid).getOrElse {
      throw BadArgumentException("Checkin=$checkinUuid not found")
    }

    validateCheckinUpdatable(checkin)

    val checkinImages = getCheckinVerificationImages(checkin, numSnapshots)

    val verificationResult = compareFacesService.verifyCheckinImages(
      checkinImages,
      faceSimilarityThreshold,
    )

    LOG.info("updating checking with automated id check result: {}, checkin={}", verificationResult, checkinUuid)
    checkin.autoIdCheck = verificationResult
    val saved = checkinRepository.saveAndFlush(checkin)
    copySnapshotsOutOfRekognition(saved)

    return verificationResult
  }

  /**
   * As of 11 Aug 2025, the Rekognition service and related S3 data are accessed through
   * a different set of credentials than the MOJ S3 bucket. For ID verification,
   * the client uploaded the data straight to Rekog bucket (using presigned URLs).
   * We now want to copy the video frames used for verification back to MOJ bucket
   * where it will be accessed by practitioners via the checkin details page.
   */
  private fun copySnapshotsOutOfRekognition(checkin: OffenderCheckin) {
    try {
      val url = rekogS3UploadService.getCheckinSnapshot(checkin, true)
      if (url != null) {
        s3UploadService.copyFromPresignedGet(url, s3UploadService.checkinObjectCoordinate(checkin, 1))
      } else {
        LOG.info("no video snapshot URL for checkin={}", checkin.uuid)
      }
    } catch (e: Exception) {
      // the images are used only for presentation, we can proceed if copy fails
      LOG.warn("Failed to copy checkin snapshots to image bucket, checkin={}", checkin.uuid, e)
    }
  }

  companion object {
    // checkin has been SUBMITTED so we no longer allow to overwrite the associated files
    private fun validateCheckinUpdatable(checkin: OffenderCheckin) {
      if (checkin.status != CheckinStatus.CREATED) {
        throw BadArgumentException("You can no longer update or add photos/videos to checkin with uuid=${checkin.uuid}")
      }
    }

    private fun validateUnscheduledNotification(now: Instant, checkin: OffenderCheckin) {
      TODO("not implemented yet")
    }

    private val LOG = LoggerFactory.getLogger(this::class.java)
  }
}

fun OffenderCheckin.isPastSubmissionDate(clock: Clock, checkinWindow: Period): Boolean {
  val submissionDate = clock.instant().atZone(clock.zone).toLocalDate()
  // dueDate + window -- last day when checkin can be submitted (till midnight),
  // the next day is when checkin submissions are no longer accepted
  val finalCheckinDate = this.dueDate.plus(checkinWindow)
  return finalCheckinDate < submissionDate
}
