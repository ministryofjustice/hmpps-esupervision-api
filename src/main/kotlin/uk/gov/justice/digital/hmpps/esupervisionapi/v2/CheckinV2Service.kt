package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.CheckinVerificationImages
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.OffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.net.URL
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** V2 Checkin Service Handles all checkin business logic for V2 */
@Service
class CheckinV2Service(
  private val clock: Clock,
  private val checkinRepository: OffenderCheckinV2Repository,
  private val offenderRepository: OffenderV2Repository,
  private val offenderEventLogRepository: OffenderEventLogV2Repository,
  private val ndiliusApiClient: NdiliusApiClient,
  private val notificationService: NotificationV2Service,
  private val checkinCreationService: CheckinCreationService,
  private val s3UploadService: S3UploadService,
  private val compareFacesService: OffenderIdVerifier,
  @Value("\${app.upload-ttl-minutes:10}") private val uploadTtlMinutes: Long,
  @Value("\${rekognition.face-similarity.threshold:90.0}")
  private val faceSimilarityThreshold: Float,
) {
  /** Get checkin by UUID Optionally includes personal details from Ndilius */
  fun getCheckin(uuid: UUID, includePersonalDetails: Boolean = false): CheckinV2Dto {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    // Fetch personal details if requested
    val personalDetails =
      if (includePersonalDetails) {
        ndiliusApiClient.getContactDetails(checkin.offender.crn)
      } else {
        null
      }

    // Get video read URL if video has been uploaded
    val videoUrl = s3UploadService.getCheckinVideo(checkin)
    val snapshotUrl = s3UploadService.getCheckinSnapshot(checkin, 0)
    val photoUrl = s3UploadService.getOffenderPhoto(checkin.offender)

    val events = offenderEventLogRepository.findAllCheckinEvents(checkin, setOf(LogEntryType.OFFENDER_CHECKIN_NOT_SUBMITTED))
    val checkinLogs = CheckinLogsV2Dto(hint = CheckinLogsHintV2.SUBSET, logs = events)

    val reviewNotes = offenderEventLogRepository.findAllCheckinEvents(checkin, setOf(LogEntryType.OFFENDER_CHECKIN_REVIEW_SUBMITTED))
    val furtherActions = if (!reviewNotes.isEmpty()) reviewNotes.first().notes else null

    return checkin.dto(personalDetails, videoUrl, snapshotUrl, checkinLogs, photoUrl, furtherActions)
  }

  /**
   * Validate personal details against Ndilius This is called before offender can proceed with
   * checkin
   */
  @Transactional
  fun validateIdentity(uuid: UUID, personalDetails: PersonalDetails): IdentityValidationResponse {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    // Verify CRN matches
    if (checkin.offender.crn != personalDetails.crn) {
      LOGGER.warn(
        "CRN mismatch for checkin {}: expected {}, got {}",
        uuid,
        checkin.offender.crn,
        personalDetails.crn,
      )
      return IdentityValidationResponse(
        verified = false,
        error = "Personal details do not match our records",
      )
    }

    // Validate against Ndilius
    val isValid = ndiliusApiClient.validatePersonalDetails(personalDetails)

    if (!isValid) {
      LOGGER.info("Identity validation failed for checkin {}", uuid)
      return IdentityValidationResponse(
        verified = false,
        error = "Personal details do not match our records",
      )
    }

    // Mark checkin as started
    if (checkin.checkinStartedAt == null) {
      checkin.checkinStartedAt = clock.instant()
      checkinRepository.save(checkin)
    }

    LOGGER.info("Identity validated successfully for checkin {}", uuid)
    return IdentityValidationResponse(verified = true)
  }

  /** Get upload locations for video and snapshots */
  fun getUploadLocations(
    uuid: UUID,
    videoContentType: String,
    snapshotContentTypes: List<String>,
  ): UploadLocationsV2Response {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    if (checkin.status != CheckinV2Status.CREATED) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Cannot upload to checkin with status: ${checkin.status}",
      )
    }

    val ttl = Duration.ofMinutes(uploadTtlMinutes)

    // Generate video upload location
    val videoUrl = s3UploadService.generatePresignedUploadUrl(checkin, videoContentType, ttl)

    // Generate snapshot upload locations
    val snapshotUrls =
      snapshotContentTypes.mapIndexed { index, contentType ->
        val url = s3UploadService.generatePresignedUploadUrl(checkin, contentType, index, ttl)
        UploadLocation(
          url = url,
          contentType = contentType,
          ttl = "PT${uploadTtlMinutes}M",
        )
      }

    return UploadLocationsV2Response(
      video =
      UploadLocation(
        url = videoUrl,
        contentType = videoContentType,
        ttl = "PT${uploadTtlMinutes}M",
      ),
      snapshots = snapshotUrls,
    )
  }

  /** Submit checkin with survey responses */
  @Transactional
  fun submitCheckin(uuid: UUID, request: SubmitCheckinV2Request): CheckinV2Dto {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    // Validate state
    if (checkin.status != CheckinV2Status.CREATED) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Checkin already submitted")
    }

    if (checkin.checkinStartedAt == null) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Identity must be verified before submission",
      )
    }

    // Verify video exists
    if (!s3UploadService.isCheckinVideoUploaded(checkin)) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Video not uploaded")
    }

    // Note: Facial recognition should be called via /video/verify endpoint BEFORE submit
    // This matches V1 behavior where facial recognition was a separate explicit step
    // The result is stored in checkin.autoIdCheck by verifyFace()

    // Update checkin
    checkin.surveyResponse = request.survey
    checkin.submittedAt = clock.instant()
    checkin.status = CheckinV2Status.SUBMITTED
    checkinRepository.save(checkin)

    LOGGER.info("Checkin submitted: {}", uuid)

    // Send notifications
    notificationService.sendCheckinSubmittedNotifications(checkin)

    // Fetch personal details for response
    val personalDetails = ndiliusApiClient.getContactDetails(checkin.offender.crn)
    return checkin.dto(personalDetails)
  }

  /**
   * Verify offender face against setup photo using AWS Rekognition This should be called after
   * video/snapshot upload but before checkin submission Allows user to see result and re-record if
   * NO_MATCH
   *
   * @param numSnapshots Number of snapshots to compare against setup photo (default 1)
   */
  @Transactional
  fun verifyFace(uuid: UUID, numSnapshots: Int = 1): FacialRecognitionResult {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    // Validate state - must be CREATED (not yet submitted)
    if (checkin.status != CheckinV2Status.CREATED) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Checkin already submitted")
    }

    // Validate offender has completed setup (is VERIFIED)
    if (checkin.offender.status != OffenderStatus.VERIFIED) {
      LOGGER.warn(
        "Facial verification attempted for offender {} with status {} (not VERIFIED)",
        checkin.offender.uuid,
        checkin.offender.status,
      )
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Offender setup not completed - cannot perform facial verification",
      )
    }

    if (numSnapshots < 1) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "numSnapshots must be at least 1")
    }

    // Verify required snapshots exist before calling Rekognition
    (0 until numSnapshots).forEach { index ->
      if (!s3UploadService.isCheckinSnapshotUploaded(checkin, index)) {
        throw ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Snapshot at index $index not uploaded",
        )
      }
    }

    // Perform facial recognition
    val result = performFacialRecognitionInternal(checkin, numSnapshots)

    return FacialRecognitionResult(result)
  }

  /** Mark checkin review as started */
  @Transactional
  fun startReview(uuid: UUID, practitionerId: ExternalUserId): CheckinV2Dto {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    if (checkin.status != CheckinV2Status.SUBMITTED && checkin.status != CheckinV2Status.EXPIRED) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Checkin must be submitted or expired before being reviewed",
      )
    }

    checkin.reviewStartedAt = clock.instant()
    checkin.reviewStartedBy = practitionerId
    checkinRepository.save(checkin)

    LOGGER.info("Review started for checkin {} by {}", uuid, practitionerId)

    val personalDetails = ndiliusApiClient.getContactDetails(checkin.offender.crn)
    val videoUrl = s3UploadService.getCheckinVideo(checkin)
    val snapshotUrl = s3UploadService.getCheckinSnapshot(checkin, 0)
    return checkin.dto(personalDetails, videoUrl, snapshotUrl)
  }

  /** Complete checkin review */
  @Transactional
  fun reviewCheckin(uuid: UUID, request: ReviewCheckinV2Request): CheckinV2Dto {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    val reviewInfo = request.appliedTo(checkin)
    offenderEventLogRepository.save(
      OffenderEventLogV2(
        comment = reviewInfo.comment,
        createdAt = clock.instant(),
        logEntryType = reviewInfo.logEntryType,
        practitioner = request.reviewedBy,
        uuid = UUID.randomUUID(),
        checkin = checkin.id,
        offender = checkin.offender,
      ),
    )

    // Update checkin
    checkin.status = reviewInfo.newStatus
    checkin.reviewedAt = clock.instant()
    checkin.reviewedBy = request.reviewedBy
    checkin.manualIdCheck = request.manualIdCheck
    checkin.riskFeedback = request.riskManagementFeedback
    checkinRepository.save(checkin)

    LOGGER.info("Checkin reviewed: {} by {}", uuid, request.reviewedBy)

    // Send notifications
    notificationService.sendCheckinReviewedNotifications(checkin)

    val personalDetails = ndiliusApiClient.getContactDetails(checkin.offender.crn)
    val videoUrl = s3UploadService.getCheckinVideo(checkin)
    val snapshotUrl = s3UploadService.getCheckinSnapshot(checkin, 0)
    return checkin.dto(personalDetails, videoUrl, snapshotUrl)
  }

  /** Annotate a checkin */
  @Transactional
  fun annotateCheckin(uuid: UUID, request: AnnotateCheckinV2Request): CheckinV2Dto {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    if (checkin.status != CheckinV2Status.REVIEWED && checkin.status != CheckinV2Status.EXPIRED) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Checkin must be reviewed before being annotated",
      )
    }

    offenderEventLogRepository.save(
      OffenderEventLogV2(
        comment = request.notes,
        createdAt = clock.instant(),
        logEntryType = LogEntryType.OFFENDER_CHECKIN_ANNOTATED,
        practitioner = request.updatedBy,
        UUID.randomUUID(),
        offender = checkin.offender,
        checkin = checkin.id,
      ),
    )

    LOGGER.info("Checkin annotated: {} by {}", uuid, request.updatedBy)

    // Send notifications - To be implemented
    // notificationService.sendCheckinUpdatedNotifications(checkin)

    val personalDetails = ndiliusApiClient.getContactDetails(checkin.offender.crn)
    return checkin.dto(personalDetails)
  }

  /** Get proxy URL for checkin video */
  fun getVideoProxyUrl(uuid: UUID): URL {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    return s3UploadService.getCheckinVideo(checkin)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found")
  }

  /** Get proxy URL for checkin snapshot */
  fun getSnapshotProxyUrl(uuid: UUID, index: Int = 0): URL {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    return s3UploadService.getCheckinSnapshot(checkin, index)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot not found")
  }

  // ========================================
  // Private Helper Methods
  // ========================================

  /**
   * Perform facial recognition and return result Used by verifyFace() endpoint - throws exceptions
   * on failure
   *
   * @param numSnapshots Number of snapshots to compare (indices 0 to numSnapshots-1)
   */
  private fun performFacialRecognitionInternal(
    checkin: OffenderCheckinV2,
    numSnapshots: Int,
  ): AutomatedIdVerificationResult {
    val offender = checkin.offender

    // Check setup photo exists
    if (!s3UploadService.isSetupPhotoUploaded(offender)) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Setup photo not available for facial recognition",
      )
    }

    // Get S3 coordinates - reference from setup, snapshots from checkin
    val referenceCoordinate = s3UploadService.setupPhotoObjectCoordinate(offender)
    val snapshotCoordinates =
      (0 until numSnapshots).map { index ->
        s3UploadService.checkinObjectCoordinate(checkin, index)
      }

    // Perform facial recognition (async, but we wait for result)
    val images =
      CheckinVerificationImages(
        reference = referenceCoordinate,
        snapshots = snapshotCoordinates,
      )

    val result = compareFacesService.verifyCheckinImages(images, faceSimilarityThreshold).join()

    // Save result to checkin
    checkin.autoIdCheck = result
    checkinRepository.save(checkin)

    when (result) {
      AutomatedIdVerificationResult.MATCH -> {
        LOGGER.info("Facial recognition MATCH for checkin {}", checkin.uuid)
      }
      AutomatedIdVerificationResult.NO_MATCH -> {
        LOGGER.info("Facial recognition NO_MATCH for checkin {}", checkin.uuid)
      }
      AutomatedIdVerificationResult.NO_FACE_DETECTED -> {
        LOGGER.warn("Facial recognition NO_FACE_DETECTED for checkin {}", checkin.uuid)
      }
      AutomatedIdVerificationResult.ERROR -> {
        LOGGER.error("Facial recognition ERROR for checkin {}", checkin.uuid)
      }
    }

    return result
  }

  @Transactional
  fun createCheckin(request: CreateCheckinV2Request): CheckinV2Dto {
    LOGGER.info(
      "DEBUG: Manually creating checkin for offender {} with due date {}",
      request.offender,
      request.dueDate,
    )

    // Use CheckinCreationService - single source of truth for checkin creation
    val checkin =
      checkinCreationService.createCheckin(
        offenderUuid = request.offender,
        dueDate = request.dueDate,
        createdBy = request.practitioner,
      )

    // Fetch personal details for response
    val personalDetails = ndiliusApiClient.getContactDetails(checkin.offender.crn)
    return checkin.dto(personalDetails)
  }

  @Transactional
  fun createCheckinByCrn(request: CreateCheckinByCrnV2Request): CheckinV2Dto {
    LOGGER.info(
      "DEBUG: Manually creating checkin by crn for offender {} with due date {}",
      request.offender,
      request.dueDate,
    )

    val offender = offenderRepository.findByCrn(request.offender).orElseThrow {
      ResponseStatusException(HttpStatus.NOT_FOUND, "Offender not found: $request.offender")
    }

    // Use CheckinCreationService - single source of truth for checkin creation
    val checkin =
      checkinCreationService.createCheckin(
        offenderUuid = offender.uuid,
        dueDate = request.dueDate,
        createdBy = request.practitioner,
      )

    // Fetch personal details for response
    val personalDetails = ndiliusApiClient.getContactDetails(checkin.offender.crn)
    return checkin.dto(personalDetails)
  }

  @Transactional
  fun sendInvite(uuid: UUID, request: CheckinNotificationV2Request): CheckinV2Dto {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    LOGGER.info("DEBUG: Manually triggering notification for checkin {}", uuid)

    val contactDetails =
      try {
        ndiliusApiClient.getContactDetails(checkin.offender.crn)
      } catch (e: Exception) {
        LOGGER.warn("Failed to fetch contact details for CRN={}", checkin.offender.crn, e)
        null
      }

    notificationService.sendCheckinCreatedNotifications(checkin, contactDetails)

    return checkin.dto(contactDetails)
  }

  @Transactional
  fun logCheckinEvent(uuid: UUID, request: LogCheckinEventV2Request): UUID {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    val eventUuid = UUID.randomUUID()

    when (request.eventType) {
      CheckinEventTypeV2.CHECKIN_OUTSIDE_ACCESS -> {
        LOGGER.warn(
          "CHECKIN_OUTSIDE_ACCESS event logged for checkin={}, comment={}",
          uuid,
          request.comment ?: "outside access",
        )
        // TODO: Store to event audit table when V2 event logging is fully implemented
        // For now, just log the event
      }
    }

    return eventUuid
  }

  /**
   * List checkins with filtering and pagination
   * @param practitionerId Required - filter by practitioner
   * @param offenderUuid Optional - filter by specific offender
   * @param useCase Optional - filter by use case (NEEDS_ATTENTION, REVIEWED, AWAITING_CHECKIN)
   * @param pageRequest Pagination and sorting
   * @return Paginated list of checkins
   */
  fun listCheckins(
    practitionerId: ExternalUserId,
    offenderUuid: UUID?,
    useCase: CheckinListUseCaseV2?,
    pageRequest: org.springframework.data.domain.PageRequest,
  ): CheckinCollectionV2Response {
    val page =
      when (useCase) {
        CheckinListUseCaseV2.NEEDS_ATTENTION ->
          checkinRepository.findNeedsAttention(
            practitionerId,
            offenderUuid,
            pageRequest,
          )
        CheckinListUseCaseV2.REVIEWED ->
          checkinRepository.findReviewed(practitionerId, offenderUuid, pageRequest)
        CheckinListUseCaseV2.AWAITING_CHECKIN ->
          checkinRepository.findAwaitingCheckin(
            practitionerId,
            offenderUuid,
            pageRequest,
          )
        null ->
          checkinRepository.findAllByCreatedBy(
            practitionerId,
            offenderUuid,
            pageRequest,
          )
      }

    val checkins = page.content.map { it.dto(null) }
    return CheckinCollectionV2Response(
      pagination =
      PaginationV2(
        pageNumber = page.pageable.pageNumber,
        pageSize = page.pageable.pageSize,
      ),
      content = checkins,
    )
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(CheckinV2Service::class.java)
  }
}

data class CheckinReviewInfo(
  val newStatus: CheckinV2Status,
  val comment: String,
  val logEntryType: LogEntryType,
)

/**
 * @return log entry comment appropriate for this checkin (depends on checkin status), and new status (possibly unchanged)
 * @throws ResponseStatusException on missing/blank comment or invalid checkin state
 */
fun ReviewCheckinV2Request.appliedTo(checkin: OffenderCheckinV2): CheckinReviewInfo {
  var errorMessage: String? = null
  var newStatus: CheckinV2Status
  var logEntryType: LogEntryType
  val comment = when (checkin.status) {
    CheckinV2Status.EXPIRED -> {
      errorMessage = "Reason for missed checkin not given"
      newStatus = CheckinV2Status.EXPIRED
      logEntryType = LogEntryType.OFFENDER_CHECKIN_NOT_SUBMITTED
      missedCheckinComment?.trim()
    }
    CheckinV2Status.SUBMITTED -> {
      errorMessage = "No review comment given"
      newStatus = CheckinV2Status.REVIEWED
      logEntryType = LogEntryType.OFFENDER_CHECKIN_REVIEW_SUBMITTED
      notes?.trim()
    }
    else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Can't review checkin withs status ${checkin.status}")
  } ?: ""
  if (comment.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage)

  assert(checkin.status.canTransitionTo(newStatus))
  return CheckinReviewInfo(newStatus, comment, logEntryType)
}
