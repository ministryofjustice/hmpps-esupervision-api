package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.services.rekognition.model.RekognitionException
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.LivenessResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.CheckinVerificationImages
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.LivenessCredentialsProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.LivenessCredentialsResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.LivenessSessionService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.OffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.net.URL
import java.time.Clock
import java.time.Duration
import java.time.Period
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** V2 Checkin Service Handles all checkin business logic for V2 */
@Service
class CheckinV2Service(
  private val clock: Clock,
  private val checkinRepository: OffenderCheckinV2Repository,
  private val offenderRepository: OffenderV2Repository,
  private val genericNotificationV2Repository: GenericNotificationV2Repository,
  private val offenderEventLogRepository: OffenderEventLogV2Repository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val notificationService: NotificationV2Service,
  private val checkinCreationService: CheckinCreationService,
  private val s3UploadService: S3UploadService,
  private val compareFacesService: OffenderIdVerifier,
  private val livenessSessionService: LivenessSessionService,
  private val livenessCredentialsService: LivenessCredentialsProvider,
  @Value("\${app.upload-ttl-minutes:10}") private val uploadTtlMinutes: Long,
  @Value("\${rekognition.face-similarity.threshold:90.0}")
  private val faceSimilarityThreshold: Float,
  @Value("\${rekognition.liveness.confidence-threshold:90.0}")
  private val livenessConfidenceThreshold: Float,
  @Value("\${rekognition.call-timeout-seconds:30}")
  private val rekognitionCallTimeoutSeconds: Long,
  private val eventAuditService: EventAuditV2Service,
  @Value("\${app.scheduling.v2-checkin-expiry.grace-period-days:3}")
  private val gracePeriodDays: Int,
) {

  private val checkinWindowPeriod = Period.ofDays(gracePeriodDays)

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

    val events = offenderEventLogRepository.findAllCheckinEvents(
      checkin,
      setOf(
        LogEntryType.OFFENDER_CHECKIN_NOT_SUBMITTED,
        LogEntryType.OFFENDER_CHECKIN_REVIEW_SUBMITTED,
        LogEntryType.OFFENDER_CHECKIN_ANNOTATED,
      ),
    )
    val checkinLogs = CheckinLogsV2Dto(hint = CheckinLogsHintV2.SUBSET, logs = events)

    val furtherActions = events.firstOrNull { it.logEntryType == LogEntryType.OFFENDER_CHECKIN_REVIEW_SUBMITTED }?.notes

    return checkin.dto(
      personalDetails,
      videoUrl,
      snapshotUrl,
      checkinLogs,
      photoUrl,
      furtherActions,
      clock = clock,
      checkinWindow = checkinWindowPeriod,
    )
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
    if (checkin.isPastSubmissionDate(clock, checkinWindowPeriod)) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Checkin is past submission date")
    }

    val ttl = Duration.ofMinutes(uploadTtlMinutes)
    val videoUrl = s3UploadService.generatePresignedUploadUrl(checkin, videoContentType, ttl)
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

    if (checkin.status != CheckinV2Status.CREATED) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Can't submit checkin with status: ${checkin.status}")
    }

    if (checkin.isPastSubmissionDate(clock, checkinWindowPeriod)) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Checkin is past submission date")
    }

    // Only verify video exists for non-liveness check-ins (liveness has no video)
    if (checkin.livenessResult == null && !s3UploadService.isCheckinVideoUploaded(checkin)) {
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

    LOGGER.info("Checkin submitted: {}, submission started at {}", uuid, checkin.checkinStartedAt)

    // Send notifications
    val contactDetails = ndiliusApiClient.getContactDetails(checkin.offender.crn)
    if (contactDetails != null) {
      notificationService.sendCheckinSubmittedNotifications(checkin, contactDetails)
    }

    eventAuditService.recordCheckinSubmitted(checkin, contactDetails)

    return checkin.dto(contactDetails, clock = clock, checkinWindow = checkinWindowPeriod)
  }

  /**
   * Verify offender face against setup photo using AWS Rekognition This should be called after
   * video/snapshot upload but before checkin submission Allows user to see result and re-record if
   * NO_MATCH
   *
   * @param numSnapshots Number of snapshots to compare against setup photo (default 1)
   */
  /**
   * This method is intentionally NOT @Transactional. The Rekognition call is done outside
   * any DB transaction, and the result is persisted via a single short write at the end.
   */
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

    // Perform facial recognition (slow I/O, no DB transaction held)
    val result = performFacialRecognition(checkin, numSnapshots)

    // Persist result in a short write transaction
    checkin.autoIdCheck = result
    checkinRepository.save(checkin)

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
    return checkin.dto(personalDetails, videoUrl, snapshotUrl, clock = clock, checkinWindow = checkinWindowPeriod)
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
    if (!checkin.sensitive && request.sensitive) {
      checkin.sensitive = true
    }
    checkinRepository.save(checkin)

    LOGGER.info("Checkin reviewed: {} by {}", uuid, request.reviewedBy)

    val contactDetails = ndiliusApiClient.getContactDetails(checkin.offender.crn)
    if (contactDetails != null) {
      notificationService.sendCheckinReviewedNotifications(checkin, contactDetails)
    }

    eventAuditService.recordCheckinReviewed(checkin, contactDetails)

    val videoUrl = s3UploadService.getCheckinVideo(checkin)
    val snapshotUrl = s3UploadService.getCheckinSnapshot(checkin, 0)
    return checkin.dto(contactDetails, videoUrl, snapshotUrl, clock = clock, checkinWindow = checkinWindowPeriod)
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
    // if check in was already marked as sensitive, it cannot be then marked as not sensitive
    if (checkin.sensitive != true && request.sensitive == true) {
      checkin.sensitive = true
      checkinRepository.save(checkin)
    }
    val annotation = offenderEventLogRepository.save(
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

    notificationService.sendCheckinUpdatedNotifications(checkin, annotation)

    val personalDetails = ndiliusApiClient.getContactDetails(checkin.offender.crn)
    return checkin.dto(personalDetails, clock = clock, checkinWindow = checkinWindowPeriod)
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
  // Liveness Methods
  // ========================================

  /** Create a Rekognition Face Liveness session for the given checkin */
  fun createLivenessSession(uuid: UUID): LivenessSessionResponse {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    if (checkin.status != CheckinV2Status.CREATED) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Cannot create liveness session for checkin with status: ${checkin.status}",
      )
    }

    val sessionId = awaitRekognition(
      future = livenessSessionService.createSession(),
      action = "create liveness session",
      checkinUuid = uuid,
    )
    LOGGER.info("Liveness session created for checkin {}: {}", uuid, sessionId)

    return LivenessSessionResponse(sessionId = sessionId)
  }

  /**
   * Block on a Rekognition async call with an upper-bound timeout and map any failure
   * to a [ResponseStatusException]. Keeps the caller off a long-running transaction.
   */
  private fun <T> awaitRekognition(
    future: CompletableFuture<T>,
    action: String,
    checkinUuid: UUID,
  ): T {
    val startNanos = System.nanoTime()
    try {
      val result = future.orTimeout(rekognitionCallTimeoutSeconds, TimeUnit.SECONDS).join()
      LOGGER.info(
        "Rekognition ({}) for checkin {} completed in {}ms",
        action,
        checkinUuid,
        elapsedMs(startNanos),
      )
      return result
    } catch (e: CompletionException) {
      val elapsedMs = elapsedMs(startNanos)
      when (val cause = e.cause) {
        is TimeoutException -> {
          LOGGER.error(
            "Timeout waiting for Rekognition ({}) for checkin {} after {}ms (limit {}s)",
            action,
            checkinUuid,
            elapsedMs,
            rekognitionCallTimeoutSeconds,
            cause,
          )
          throw ResponseStatusException(
            HttpStatus.GATEWAY_TIMEOUT,
            "Rekognition call timed out: $action",
            cause,
          )
        }
        is RekognitionException -> {
          LOGGER.error(
            "Rekognition error ({}) for checkin {} after {}ms: awsErrorCode={}, statusCode={}, message={}",
            action,
            checkinUuid,
            elapsedMs,
            cause.awsErrorDetails()?.errorCode(),
            cause.statusCode(),
            cause.message,
            cause,
          )
          throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "Rekognition call failed ($action): ${cause.awsErrorDetails()?.errorMessage() ?: cause.message}",
            cause,
          )
        }
        else -> {
          LOGGER.error(
            "Unexpected async error ({}) for checkin {} after {}ms",
            action,
            checkinUuid,
            elapsedMs,
            cause ?: e,
          )
          throw ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Rekognition call failed ($action)",
            cause ?: e,
          )
        }
      }
    }
  }

  private fun elapsedMs(startNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

  /** Get scoped temporary AWS credentials for the browser liveness detector */
  fun getLivenessCredentials(uuid: UUID): LivenessCredentialsResponse {
    // Validate checkin exists
    checkinRepository.findByUuid(uuid).orElseThrow {
      ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
    }

    return livenessCredentialsService.getCredentials()
  }

  /**
   * Verify liveness session results and perform face comparison.
   * Gets the liveness result from Rekognition, checks confidence threshold,
   * then compares the liveness reference image against the offender's setup photo.
   */
  /**
   * This method is intentionally NOT @Transactional. It performs several slow I/O calls
   * (Rekognition, S3) and we must not hold a database connection for their duration.
   * Reads and writes each happen in their own short, auto-managed transactions via the
   * repository. The outer flow is: load → validate → Rekognition → S3 → face compare → persist.
   */
  fun verifyLiveness(uuid: UUID, sessionId: String): LivenessVerificationResponse {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }

    if (checkin.status != CheckinV2Status.CREATED) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Checkin already submitted")
    }

    if (checkin.offender.status != OffenderStatus.VERIFIED) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Offender setup not completed - cannot perform liveness verification",
      )
    }

    // Get liveness session results from Rekognition (slow I/O, no DB transaction held)
    val livenessResult = awaitRekognition(
      future = livenessSessionService.getSessionResults(sessionId),
      action = "get liveness session results",
      checkinUuid = uuid,
    )
    val confidence = livenessResult.confidence()
    val isLive = confidence >= livenessConfidenceThreshold
    val livenessStatus = if (isLive) LivenessResult.LIVE else LivenessResult.NOT_LIVE

    LOGGER.info(
      "Liveness result for checkin {}: confidence={}, threshold={}, isLive={}",
      uuid,
      confidence,
      livenessConfidenceThreshold,
      isLive,
    )

    // Extract the reference image bytes from the session
    val referenceImage = livenessResult.referenceImage()
    val imageBytes = referenceImage?.bytes()?.asByteArray()
    if (referenceImage == null || imageBytes == null || imageBytes.isEmpty()) {
      LOGGER.warn("Liveness session {} has no reference image for face comparison", sessionId)
      checkin.livenessResult = livenessStatus
      checkin.livenessConfidence = confidence
      checkin.autoIdCheck = AutomatedIdVerificationResult.ERROR
      checkinRepository.save(checkin)
      return LivenessVerificationResponse(
        isLive = isLive,
        livenessConfidence = confidence,
        result = AutomatedIdVerificationResult.ERROR,
      )
    }

    // S3 uploads (slow I/O, no DB transaction held)
    if (livenessResult.hasAuditImages()) {
      livenessResult.auditImages().forEachIndexed { index, image ->
        val auditBytes = image.bytes().asByteArray()
        if (auditBytes != null && auditBytes.isNotEmpty()) {
          s3UploadService.uploadCheckinSnapshot(checkin, index + 1, auditBytes, "image/jpeg")
          LOGGER.info("Uploaded liveness audit image for checkin {} ({} bytes)", uuid, auditBytes.size)
        }
      }
    }

    // Upload reference image to S3 as checkin snapshot so compareFaces can access it
    s3UploadService.uploadCheckinSnapshot(checkin, 0, imageBytes, "image/jpeg")
    LOGGER.info("Uploaded liveness reference image for checkin {} ({} bytes)", uuid, imageBytes.size)

    // Perform face comparison (slow I/O, no DB transaction held)
    val result = performFacialRecognition(checkin, numSnapshots = 1)

    // Persist both results in a single short write transaction
    checkin.livenessResult = livenessStatus
    checkin.livenessConfidence = confidence
    checkin.autoIdCheck = result
    checkinRepository.save(checkin)

    return LivenessVerificationResponse(
      isLive = isLive,
      livenessConfidence = confidence,
      result = result,
    )
  }

  // ========================================
  // Private Helper Methods
  // ========================================

  /**
   * Run the face-compare call against Rekognition and return the result.
   *
   * Does NOT persist — callers are responsible for saving the result to the checkin in
   * their own short transaction. Called from non-transactional code so the Rekognition
   * wait never holds a DB connection.
   *
   * @param numSnapshots Number of snapshots to compare (indices 0 to numSnapshots-1)
   */
  private fun performFacialRecognition(
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

    val images =
      CheckinVerificationImages(
        reference = referenceCoordinate,
        snapshots = snapshotCoordinates,
      )

    val result = awaitRekognition(
      future = compareFacesService.verifyCheckinImages(images, faceSimilarityThreshold),
      action = "compare faces",
      checkinUuid = checkin.uuid,
    )

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
    return checkin.dto(personalDetails, clock = clock, checkinWindow = checkinWindowPeriod)
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
    return checkin.dto(personalDetails, clock = clock, checkinWindow = checkinWindowPeriod)
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

    if (contactDetails != null) {
      notificationService.sendCheckinCreatedNotifications(checkin, contactDetails)
    } else {
      LOGGER.warn("Skipping manual notification for checkin {}: contact details not found", uuid)
    }

    return checkin.dto(contactDetails, clock = clock, checkinWindow = checkinWindowPeriod)
  }

  @Transactional
  fun sendReminder(uuid: UUID): CheckinV2Dto {
    val checkin =
      checkinRepository.findByUuid(uuid).orElseThrow {
        ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
      }
    // check to see if a reminder has been sent in the last 30 mins
    val notificationThrottleWindow = clock.instant().minus(Duration.ofMinutes(30))
    val notificationAlreadySent = genericNotificationV2Repository.hasNotificationBeenSent(
      offender = checkin.offender,
      eventType = NotificationType.OffenderCheckinReminder.name,
      cutoffTime = notificationThrottleWindow,
    )

    if (notificationAlreadySent) {
      LOGGER.info("Throttling manual reminder for checkin {}: notification was sent in the last 30 minutes", uuid)
      throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "A reminder was sent recently. Please wait 30 minutes.")
    }

    LOGGER.info("DEBUG: Manually triggering REMINDER for checkin {}", uuid)
    val contactDetails =
      try {
        ndiliusApiClient.getContactDetails(checkin.offender.crn)
      } catch (e: Exception) {
        LOGGER.warn("Failed to fetch contact details for CRN={}", checkin.offender.crn, e)
        null
      }

    if (contactDetails != null) {
      notificationService.sendCheckinReminderNotifications(checkin, contactDetails)
    } else {
      LOGGER.warn("Skipping manual reminder notification for checkin {}: contact details not found", uuid)
    }

    return checkin.dto(contactDetails, clock = clock, checkinWindow = checkinWindowPeriod)
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

    val checkins = page.content.map { it.dto(null, clock = clock, checkinWindow = checkinWindowPeriod) }
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
      newStatus = CheckinV2Status.REVIEWED
      logEntryType = LogEntryType.OFFENDER_CHECKIN_REVIEW_SUBMITTED
      notes?.trim()
    }
    else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Can't review checkin withs status ${checkin.status}")
  } ?: ""
  if (checkin.status == CheckinV2Status.EXPIRED && comment.isBlank()) {
    throw ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage)
  }

  assert(checkin.status.canTransitionTo(newStatus))
  return CheckinReviewInfo(newStatus, comment, logEntryType)
}
