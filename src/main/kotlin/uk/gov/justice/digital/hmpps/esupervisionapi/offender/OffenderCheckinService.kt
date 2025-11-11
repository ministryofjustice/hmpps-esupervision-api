package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.events.CheckinAdditionalInformation
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DOMAIN_EVENT_VERSION
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DomainEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.events.PersonReference
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.GenericNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationContextType
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationResults
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.OffenderCheckinInviteMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.OffenderCheckinSubmittedMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PractitionerCheckinSubmittedMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.SingleNotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.saveNotifications
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.CheckinVerificationImages
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.OffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinEventRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinNotificationRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinReviewRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinUploadLocationResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CreateCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.toPagination
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
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
  private val notificationRepository: CheckinNotificationRepository,
  @Qualifier("rekognitionS3") private val rekogS3UploadService: S3UploadService,
  private val compareFacesService: OffenderIdVerifier,
  @Value("\${app.scheduling.checkin-notification.window:72h}") val checkinWindow: Duration,
  @Value("\${rekognition.face-similarity.threshold}") val faceSimilarityThreshold: Float,
  private val offenderEventLogRepository: OffenderEventLogRepository,
  private val practitionerRepository: PractitionerRepository,
  private val eventPublisher: DomainEventPublisher,
  private val appConfig: AppConfig,
  private val genericNotificationRepository: GenericNotificationRepository,
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
    if (createCheckin.dueDate < clock.today()) {
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
      reviewStartedAt = null,
      status = CheckinStatus.CREATED,
      surveyResponse = null,
      dueDate = createCheckin.dueDate,
      autoIdCheck = null,
      manualIdCheck = null,
    )

    val saved = saveCheckin(checkin, offender, createCheckin)

    // notify PoP of checkin invite
    val inviteMessage = OffenderCheckinInviteMessage.fromCheckin(checkin, checkinWindowPeriod)
    val inviteResults = this.notificationService.sendMessage(inviteMessage, checkin.offender, notificationContext)

    if (notificationContext.type == NotificationContextType.SINGLE) {
      notificationRepository.saveInviteNotifications(saved, notificationContext, inviteResults)
    }

    return CheckinCreationInfo(
      saved.dto(this.s3UploadService),
      inviteResults,
    )
  }

  /**
   * @return the saved checkin
   * @throws BadArgumentException when checkin already exists for offender on given date
   */
  private fun saveCheckin(
    checkin: OffenderCheckin,
    offender: Offender,
    createCheckin: CreateCheckinRequest,
  ): OffenderCheckin = try {
    checkinRepository.save(checkin)
  } catch (e: DataIntegrityViolationException) {
    val violation = e.cause as? ConstraintViolationException
    if (violation?.constraintName == "one_checkin_per_day") {
      throw BadArgumentException("Cannot create another checkin for offender ${offender.uuid} on ${createCheckin.dueDate}")
    }
    throw e
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
    val offenderNotifContext = SingleNotificationContext.from(offenderConfirmation, clock)
    val offenderNotifResults = this.notificationService.sendMessage(offenderConfirmation, checkin.offender, offenderNotifContext)

    try {
      genericNotificationRepository.saveNotifications(offenderConfirmation.messageType, offenderNotifContext, offender, offenderNotifResults)
    } catch (e: Exception) {
      LOG.warn("Failed to persist offender checkin submitted notifications for checkin={}, reference={}", checkin.uuid, offenderNotifResults.results.map { it.notificationId }, e)
    }

    if (offender.crn != null) {
      eventPublisher.publish(checkinReceivedEvent(offender, checkin, now))
    } else {
      LOG.warn("Missing a CRN for offender={}", offender.uuid)
    }

    return checkin.dto(this.s3UploadService)
  }

  private fun checkinReceivedEvent(offender: Offender, checkin: OffenderCheckin, now: Instant): DomainEvent {
    val checkinUrl = appConfig.checkinDashboardUrl(checkin.uuid)
    val domainEvent = DomainEvent(
      DomainEventType.CHECKIN_RECEIVED.type,
      version = DOMAIN_EVENT_VERSION,
      null,
      now.atZone(clock.zone),
      DomainEventType.CHECKIN_RECEIVED.description,
      CheckinAdditionalInformation(checkinUrl.toURL().toString()),
      PersonReference(listOf(PersonReference.PersonIdentifier("CRN", offender.crn!!))),
    )
    return domainEvent
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

  fun getCheckins(practitionerId: ExternalUserId, offenderId: UUID?, pageRequest: PageRequest, useCase: CheckinListUseCase? = null): CollectionDto<OffenderCheckinDto> {
    val page = when (useCase) {
      CheckinListUseCase.NEEDS_ATTENTION -> checkinRepository.findNeedsAttention(practitionerId, offenderId, pageRequest)
      CheckinListUseCase.REVIEWED -> checkinRepository.findReviewed(practitionerId, offenderId, pageRequest)
      CheckinListUseCase.AWAITING_CHECKIN -> checkinRepository.findAwaitingCheckin(practitionerId, offenderId, pageRequest)
      null -> checkinRepository.findAllByCreatedBy(practitionerId, offenderId, pageRequest)
    }
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
   * Send checkin invite notification to the offender, potentially updates
   * the due date of the checkin (if triggered on a day later than due date).
   *
   * This can be called to trigger a checkin notification (for the offender) outside
   * the periodically executing job. For example, an offender was just added,
   * and the first checkin is supposed to happen today (likely after the job
   * is already finished).
   */
  @Transactional
  fun unscheduledNotification(checkinUuid: UUID, notificationRequest: CheckinNotificationRequest): OffenderCheckinDto {
    val checkin = checkinRepository.findByUuid(checkinUuid).getOrElse {
      throw BadArgumentException("Checkin=$checkinUuid not found")
    }
    validateCheckinUpdatable(checkin)
    // we should be only updating the latest checkin
    val latestCheckins = checkinRepository.findFirst3ByOffenderOrderByCreatedAtDesc(checkin.offender)
    if (latestCheckins[0].uuid != checkinUuid) {
      throw BadArgumentException("Checkin=$checkinUuid possibly expired. offender=${checkin.offender.uuid}")
    }
    val today = clock.today()
    if (today >= checkin.dueDate.plus(checkinWindowPeriod)) {
      throw BadArgumentException("Checkin due date has passed for checkin=${checkin.uuid}")
    }

    val practitioner = notificationRequest.practitioner
    if (checkin.dueDate != today) {
      val originalDueDate = checkin.dueDate
      checkin.dueDate = today
      checkinRepository.save(checkin)
      offenderEventLogRepository.save(
        OffenderEventLog(
          UUID.randomUUID(),
          LogEntryType.OFFENDER_CHECKIN_RESCHEDULED,
          comment = "checkin rescheduled: $originalDueDate -> $today",
          practitioner = practitioner,
          offender = checkin.offender,
          checkin = checkin,
        ),
      )
      LOG.info("Updated checkin due date to $today for checkin=${checkin.uuid}, by practitioner=$practitioner")
    }

    val inviteMessage = OffenderCheckinInviteMessage.fromCheckin(checkin, checkinWindowPeriod)
    val notificationContext = SingleNotificationContext.from(inviteMessage, clock)
    val inviteResults = this.notificationService.sendMessage(
      inviteMessage,
      checkin.offender,
      notificationContext,
    )
    notificationRepository.saveInviteNotifications(checkin, notificationContext, inviteResults)

    return checkin.dto(this.s3UploadService)
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

  fun checkinEvent(uuid: UUID, request: CheckinEventRequest): UUID {
    LOG.info("CheckinEventRequest(eventType={}) for checkin={}", request.eventType, uuid)
    val checkin = checkinRepository.findByUuid(uuid).getOrElse {
      throw NoResourceFoundException(HttpMethod.POST, "/offender_checkins/$uuid")
    }
    when (request.eventType) {
      CheckinEventType.CHECKIN_OUTSIDE_ACCESS -> {
        if (checkin.status != CheckinStatus.CREATED) {
          throw BadArgumentException("Invalid event ${request.eventType} for checkin of status ${checkin.status}")
        }
        val event = OffenderEventLog(UUID.randomUUID(), LogEntryType.OFFENDER_CHECKIN_OUTSIDE_ACCESS, request.comment ?: "outside access", checkin.offender.practitioner, checkin.offender, checkin)
        offenderEventLogRepository.save(event)
        return event.uuid
      }

      CheckinEventType.REVIEW_STARTED -> {
        // Every time this event is called, the reviewStartedAt time is updated to now
        checkin.reviewStartedAt = clock.instant()
        checkinRepository.save(checkin)
        return checkin.uuid
      }
    }
  }

  companion object {
    // checkin has been SUBMITTED so we no longer allow to overwrite the associated files
    private fun validateCheckinUpdatable(checkin: OffenderCheckin) {
      if (checkin.status != CheckinStatus.CREATED) {
        throw BadArgumentException("You can no longer update or add photos/videos to checkin with uuid=${checkin.uuid}")
      }
      if (checkin.offender.status != OffenderStatus.VERIFIED) {
        throw BadArgumentException("Unscheduled notification for offender of status ${checkin.offender.status}")
      }
    }

    private val LOG = LoggerFactory.getLogger(this::class.java)
  }
}

fun OffenderCheckin.isPastSubmissionDate(clock: Clock, checkinWindow: Period): Boolean {
  assert(checkinWindow.days > 1)
  val submissionDate = clock.today()
  val finalCheckinDate = if (checkinWindow.days <= 1) {
    this.dueDate
  } else {
    this.dueDate.plus(checkinWindow.minusDays(1))
  }
  return finalCheckinDate < submissionDate
}

private fun CheckinNotificationRepository.saveInviteNotifications(
  checkin: OffenderCheckin,
  context: NotificationContext,
  results: NotificationResults,
) {
  if (results.results.isNotEmpty()) {
    val checkinNotifications = results.results.map {
      CheckinNotification(
        notificationId = it.notificationId,
        checkin = checkin.uuid,
        reference = context.reference,
      )
    }
    this.saveAll(checkinNotifications)
  }
}
