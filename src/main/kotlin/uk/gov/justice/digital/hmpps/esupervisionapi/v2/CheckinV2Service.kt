package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.services.rekognition.model.GetFaceLivenessSessionResultsResponse
import software.amazon.awssdk.services.rekognition.model.RekognitionException
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinRequestApplicator.applyRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.LivenessResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.CheckinVerificationImages
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.FacialRecognitionOutcome
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.LivenessCredentialsProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.LivenessCredentialsResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.LivenessSessionService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.OffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.resolveUploadHash
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
  private val checkinPersistenceService: CheckinPersistenceService,
  @param:Value("\${app.upload-ttl-minutes:10}") private val uploadTtlMinutes: Long,
  @param:Value("\${rekognition.face-similarity.threshold:90.0}")
  private val faceSimilarityThreshold: Float,
  @param:Value("\${rekognition.liveness.confidence-threshold:90.0}")
  private val livenessConfidenceThreshold: Float,
  @param:Value("\${rekognition.call-timeout-seconds:30}")
  private val rekognitionCallTimeoutSeconds: Long,
  private val objectMapper: ObjectMapper,
  @param:Value("\${app.scheduling.v2-checkin-expiry.grace-period-days:3}")
  private val gracePeriodDays: Int,
  private val appConfig: AppConfig,
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
    hashes: CheckinUploadHashesRequest? = null,
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

    val requireHash = appConfig.enabledFeatures.contains(Feature.ESUP_1672_REQUIRE_UPLOAD_CONTENT_HASH)

    val ttl = Duration.ofMinutes(uploadTtlMinutes)
    val ttlString = "PT${uploadTtlMinutes}M"
    // Video URL is left unbound to a content hash — no client uploads videos via this endpoint.
    val videoPresigned = s3UploadService.generatePresignedUpload(checkin, videoContentType, ttl, null)
    val snapshotUploads =
      snapshotContentTypes.mapIndexed { index, contentType ->
        val snapHash = resolveUploadHash(
          hashes?.snapshots?.getOrNull(index)?.sha256,
          requireHash,
          "snapshot[$index]",
        )
        LOGGER.info(
          "upload_hash.received endpoint=/v2/offender_checkins/upload_location slot=snapshot[{}] received={}",
          index,
          snapHash != null,
        )
        val presigned = s3UploadService.generatePresignedUpload(checkin, contentType, index, ttl, snapHash)
        UploadLocation(
          url = presigned.url,
          contentType = contentType,
          ttl = ttlString,
          requiredHeaders = presigned.requiredHeaders.takeIf { it.isNotEmpty() },
        )
      }

    return UploadLocationsV2Response(
      video =
      UploadLocation(
        url = videoPresigned.url,
        contentType = videoContentType,
        ttl = ttlString,
        requiredHeaders = videoPresigned.requiredHeaders.takeIf { it.isNotEmpty() },
      ),
      snapshots = snapshotUploads,
    )
  }

  /** Submit checkin with survey responses */
  fun submitCheckin(uuid: UUID, request: SubmitCheckinV2Request): CheckinV2Dto {
    val maybeCheckin = checkinPersistenceService.findCheckin(uuid)
    val checkin = validateCheckinForSubmission(uuid, maybeCheckin)
    checkin.applyRequest(request, clock)

    val contactDetails = ndiliusApiClient.getContactDetails(checkin.offender.crn)
    val event = checkin.toCheckinSubmittedEvent(contactDetails, clock = clock, checkinWindow = checkinWindowPeriod)
    checkinPersistenceService.checkinSubmission(checkin, event)

    LOGGER.info("Checkin submitted: {}, submission started at {}", uuid, checkin.checkinStartedAt)
    return event.checkin
  }

  /**
   * Returns the validated value or throws.
   */
  private fun validateCheckinForSubmission(uuid: UUID, maybeCheckin: OffenderCheckinV2?): OffenderCheckinV2 {
    val checkin = maybeCheckin ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
    if (checkin.status != CheckinV2Status.CREATED) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Can't submit checkin with status: ${checkin.status}")
    }

    if (checkin.isPastSubmissionDate(clock, checkinWindowPeriod)) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Checkin is past submission date")
    }

    // Only verify video exists for non-liveness check-ins (liveness has no video)
    if (!checkin.livenessEnabled && !s3UploadService.isCheckinVideoUploaded(checkin)) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Video not uploaded")
    }
    return checkin
  }

  /**
   * Verify offender face against setup photo using AWS Rekognition This should be called after
   * video/snapshot upload but before checkin submission Allows user to see result and re-record if
   * NO_MATCH
   *
   * This method is intentionally NOT @Transactional. The Rekognition call is done outside
   * any DB transaction, and the result is persisted via a single short write at the end.
   *
   * @param numSnapshots Number of snapshots to compare against setup photo (default 1)
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
    val outcome = performFacialRecognition(checkin, numSnapshots)

    // Record every non-MATCH outcome so retries leave an audit trail.
    if (outcome.result != AutomatedIdVerificationResult.MATCH) {
      recordRekognitionFailure(
        checkin,
        LogEntryType.OFFENDER_CHECKIN_FACE_MATCH_FAILED,
        attemptNotes(
          "result" to outcome.result.name,
          "similarity" to outcome.topSimilarity,
          "errorCode" to outcome.errorCode,
        ),
      )
    }

    checkin.autoIdCheck = outcome.result
    checkin.autoIdCheckScore = outcome.topSimilarity
    checkinRepository.save(checkin)

    return FacialRecognitionResult(outcome.result)
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
  fun reviewCheckin(uuid: UUID, request: ReviewCheckinV2Request): CheckinV2Dto {
    val checkin = checkinPersistenceService.findCheckin(uuid) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Checkin not found: $uuid")
    if (checkin.status != CheckinV2Status.SUBMITTED && checkin.status != CheckinV2Status.EXPIRED) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Checkin must be submitted or expired before being reviewed. Current status: ${checkin.status}")
    }

    val contactDetails = ndiliusApiClient.getContactDetails(checkin.offender.crn)
    val videoUrl = s3UploadService.getCheckinVideo(checkin)
    val snapshotUrl = s3UploadService.getCheckinSnapshot(checkin, 0)

    val reviewInfo = request.newValuesFor(checkin.status)
    checkin.applyRequest(request, reviewInfo, clock)
    val event = checkin.toCheckinReviewedEvent(contactDetails, clock = clock, checkinWindow = checkinWindowPeriod, videoUrl = videoUrl, snapshotUrl = snapshotUrl)
    checkinPersistenceService.checkinReview(checkin, event, reviewInfo)

    if (checkin.manualIdCheck == ManualIdVerificationResult.MATCH) {
      s3UploadService.deleteCheckinSnapshot(uuid, 0)
      s3UploadService.deleteCheckinVideo(uuid)
      return checkin.dto(contactDetails, null, null, clock = clock, checkinWindow = checkinWindowPeriod)
    }

    LOGGER.info("Checkin reviewed: {} by {}", uuid, request.reviewedBy)
    return event.checkin
  }

  /** Annotate a checkin */
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

    checkin.sensitive = checkin.sensitive || request.sensitive
    val personalDetails = ndiliusApiClient.getContactDetails(checkin.offender.crn)
    val event = PartialCheckinAnnotatedEvent(
      checkinId = checkin.id,
      offenderId = checkin.offender.id,
      checkin = checkin.dto(personalDetails, clock = clock, checkinWindow = checkinWindowPeriod),
      practitionerId = request.updatedBy,
      offenderContactPreference = checkin.offender.contactPreference,
    )
    checkinPersistenceService.checkinAnnotation(checkin, event, request)
    LOGGER.info("Checkin annotated: {} by {}", uuid, request.updatedBy)

    return event.checkin
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

    // Starting (or restarting) liveness invalidates any face-match results from a prior attempt.
    // This matters when the offender is retrying liveness in the same session, or returning to a
    // checkin where liveness previously passed but didn't get submitted: without clearing here,
    // a later fallback video could complete on top of stale livenessResult/autoIdCheck values
    // and the practitioner gate (livenessEnabled && livenessResult == LIVE) would read the
    // wrong outcome.
    checkin.livenessEnabled = true
    checkin.livenessResult = null
    checkin.livenessConfidence = null
    checkin.autoIdCheck = null
    checkin.autoIdCheckScore = null
    checkinRepository.save(checkin)

    val sessionId = awaitRekognition(
      future = livenessSessionService.createSession(),
      action = "create liveness session",
      checkinUuid = uuid,
      onFailure = { kind, errorCode, elapsedMs ->
        recordRekognitionFailure(
          checkin,
          LogEntryType.OFFENDER_CHECKIN_LIVENESS_FAILED,
          attemptNotes(
            "result" to failureResultLabel(kind),
            "errorCode" to errorCode,
            "action" to "create liveness session",
            "elapsedMs" to elapsedMs,
          ),
        )
      },
    )
    LOGGER.info("Liveness session created for checkin {}: {}", uuid, sessionId)

    return LivenessSessionResponse(sessionId = sessionId)
  }

  /**
   * Block on a Rekognition async call with an upper-bound timeout and map any failure
   * to a [ResponseStatusException]. Keeps the caller off a long-running transaction.
   *
   * [onFailure] is invoked once before any throw — callers use it to record an attempt
   * audit row capturing the failure kind and timing.
   */
  private fun <T> awaitRekognition(
    future: CompletableFuture<T>,
    action: String,
    checkinUuid: UUID,
    onFailure: ((kind: RekognitionFailureKind, errorCode: String?, elapsedMs: Long) -> Unit)? = null,
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
          onFailure?.invoke(RekognitionFailureKind.TIMEOUT, null, elapsedMs)
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
          val errorCode = cause.awsErrorDetails()?.errorCode()
          onFailure?.invoke(RekognitionFailureKind.REKOG_ERROR, errorCode, elapsedMs)
          LOGGER.error(
            "Rekognition error ({}) for checkin {} after {}ms: awsErrorCode={}, statusCode={}, message={}",
            action,
            checkinUuid,
            elapsedMs,
            errorCode,
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
          onFailure?.invoke(RekognitionFailureKind.OTHER, null, elapsedMs)
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

  /**
   * Build a small JSON object for the event log entry's comment column from the supplied
   * key/value pairs. Null values are omitted so the JSON only carries meaningful fields.
   */
  private fun attemptNotes(vararg fields: Pair<String, Any?>): String {
    val map = fields.toMap().filterValues { it != null }
    return objectMapper.writeValueAsString(map)
  }

  private fun failureResultLabel(kind: RekognitionFailureKind): String = when (kind) {
    RekognitionFailureKind.TIMEOUT -> "TIMEOUT"
    RekognitionFailureKind.REKOG_ERROR, RekognitionFailureKind.OTHER -> "ERROR"
  }

  /**
   * Append an entry to [offender_event_log_v2] capturing a failed Rekognition attempt.
   */
  private fun recordRekognitionFailure(
    checkin: OffenderCheckinV2,
    type: LogEntryType,
    notes: String,
  ) {
    try {
      offenderEventLogRepository.save(
        OffenderEventLogV2(
          comment = notes,
          sensitive = false,
          createdAt = clock.instant(),
          logEntryType = type,
          practitioner = SYSTEM_PRACTITIONER,
          uuid = UUID.randomUUID(),
          checkin = checkin.id,
          offender = checkin.offender,
        ),
      )
      LOGGER.info("Recorded {} event log entry for checkin={}", type, checkin.uuid)
    } catch (e: Exception) {
      LOGGER.error("Failed to record {} event log entry for checkin={}: {}", type, checkin.uuid, e.message, e)
    }
  }

  /**
   * Record a client-side liveness failure reported by the browser. The Amplify
   * FaceLivenessDetector raises onError before the session reaches Rekognition for
   * cases like CAMERA_ACCESS_ERROR, MULTIPLE_FACES_ERROR, TIMEOUT etc. — the server
   * never sees those otherwise.
   *
   * State validation matches sibling liveness endpoints: rejects calls against a
   * non-CREATED checkin or an unverified offender, so a caller who knows a stale UUID
   * can't add noise to a finished checkin's log.
   *
   * Note: this endpoint has no rate limiting (consistent with other liveness endpoints)
   * — a misbehaving but authenticated session could spam rows. Blast radius is limited
   * to that offender's own log entries; addressing this would be a project-wide change.
   */
  fun recordLivenessClientFailure(uuid: UUID, state: String?) {
    val checkin = loadCheckinForLivenessVerify(uuid)
    recordRekognitionFailure(
      checkin,
      LogEntryType.OFFENDER_CHECKIN_LIVENESS_FAILED,
      attemptNotes(
        "result" to "CLIENT_ERROR",
        "state" to state,
      ),
    )
  }

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
    val checkin = loadCheckinForLivenessVerify(uuid)

    val livenessResult = fetchLivenessSessionResults(checkin, sessionId)
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

    if (!isLive) {
      recordRekognitionFailure(
        checkin,
        LogEntryType.OFFENDER_CHECKIN_LIVENESS_FAILED,
        attemptNotes(
          "result" to LivenessResult.NOT_LIVE.name,
          "confidence" to confidence,
          "sessionId" to sessionId,
        ),
      )
    }

    val referenceImage = livenessResult.referenceImage()
    val imageBytes = referenceImage?.bytes()?.asByteArray()
    if (referenceImage == null || imageBytes == null || imageBytes.isEmpty()) {
      return handleMissingReferenceImage(checkin, sessionId, livenessStatus, confidence, isLive)
    }

    uploadLivenessImagesToS3(checkin, imageBytes)

    // Perform face comparison (slow I/O, no DB transaction held)
    val outcome = performFacialRecognition(checkin, numSnapshots = 1)

    // Record every non-MATCH outcome so retries leave an audit trail.
    if (outcome.result != AutomatedIdVerificationResult.MATCH) {
      recordRekognitionFailure(
        checkin,
        LogEntryType.OFFENDER_CHECKIN_FACE_MATCH_FAILED,
        attemptNotes(
          "result" to outcome.result.name,
          "similarity" to outcome.topSimilarity,
          "errorCode" to outcome.errorCode,
          "sessionId" to sessionId,
        ),
      )
    }

    // Persist both results in a single short write transaction
    checkin.livenessResult = livenessStatus
    checkin.livenessConfidence = confidence
    checkin.autoIdCheck = outcome.result
    checkin.autoIdCheckScore = outcome.topSimilarity
    checkinRepository.save(checkin)

    return LivenessVerificationResponse(
      isLive = isLive,
      livenessConfidence = confidence,
      result = outcome.result,
    )
  }

  /**
   * Load the checkin and assert it's in a state where liveness verification is meaningful:
   * still CREATED (not already submitted) and the offender has completed setup.
   */
  private fun loadCheckinForLivenessVerify(uuid: UUID): OffenderCheckinV2 {
    val checkin = checkinRepository.findByUuid(uuid).orElseThrow {
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
    return checkin
  }

  /**
   * Fetch the Rekognition liveness session results, recording a liveness-failed audit row
   * if the AWS call itself errors or times out.
   */
  private fun fetchLivenessSessionResults(
    checkin: OffenderCheckinV2,
    sessionId: String,
  ): GetFaceLivenessSessionResultsResponse = awaitRekognition(
    future = livenessSessionService.getSessionResults(sessionId),
    action = "get liveness session results",
    checkinUuid = checkin.uuid,
    onFailure = { kind, errorCode, elapsedMs ->
      recordRekognitionFailure(
        checkin,
        LogEntryType.OFFENDER_CHECKIN_LIVENESS_FAILED,
        attemptNotes(
          "result" to failureResultLabel(kind),
          "errorCode" to errorCode,
          "action" to "get liveness session results",
          "sessionId" to sessionId,
          "elapsedMs" to elapsedMs,
        ),
      )
    },
  )

  /**
   * Handle the (rare) case where Rekognition succeeded but didn't return a reference image
   * we can compare. We can't run face match, so persist liveness state, mark autoIdCheck
   * as ERROR, and surface that to the UI.
   */
  private fun handleMissingReferenceImage(
    checkin: OffenderCheckinV2,
    sessionId: String,
    livenessStatus: LivenessResult,
    confidence: Float,
    isLive: Boolean,
  ): LivenessVerificationResponse {
    LOGGER.warn("Liveness session {} has no reference image for face comparison", sessionId)
    recordRekognitionFailure(
      checkin,
      LogEntryType.OFFENDER_CHECKIN_FACE_MATCH_FAILED,
      attemptNotes(
        "result" to AutomatedIdVerificationResult.ERROR.name,
        "reason" to "no reference image from liveness session",
        "sessionId" to sessionId,
      ),
    )
    checkin.livenessResult = livenessStatus
    checkin.livenessConfidence = confidence
    checkin.autoIdCheck = AutomatedIdVerificationResult.ERROR
    checkin.autoIdCheckScore = null
    checkinRepository.save(checkin)
    return LivenessVerificationResponse(
      isLive = isLive,
      livenessConfidence = confidence,
      result = AutomatedIdVerificationResult.ERROR,
    )
  }

  /**
   * Upload the liveness reference image as snapshot 0, where compareFaces will read it.
   * Slow I/O, intentionally outside any DB transaction.
   */
  private fun uploadLivenessImagesToS3(
    checkin: OffenderCheckinV2,
    referenceImageBytes: ByteArray,
  ) {
    s3UploadService.uploadCheckinSnapshot(checkin, 0, referenceImageBytes, "image/jpeg")
    LOGGER.info("Uploaded liveness reference image for checkin {} ({} bytes)", checkin.uuid, referenceImageBytes.size)
  }

  // ========================================
  // Private Helper Methods
  // ========================================

  /**
   * Run the face-compare call against Rekognition and return the outcome (result + score).
   *
   * Does NOT persist — callers are responsible for saving to the checkin in their own
   * short transaction. Called from non-transactional code so the Rekognition wait never
   * holds a DB connection. I/O failures (timeout / Rekognition error) are recorded as
   * CHECKIN_FACE_MATCH_FAILED audit rows here before the exception propagates.
   *
   * @param numSnapshots Number of snapshots to compare (indices 0 to numSnapshots-1)
   */
  private fun performFacialRecognition(
    checkin: OffenderCheckinV2,
    numSnapshots: Int,
  ): FacialRecognitionOutcome {
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

    val outcome = awaitRekognition(
      future = compareFacesService.verifyCheckinImages(images, faceSimilarityThreshold),
      action = "compare faces",
      checkinUuid = checkin.uuid,
      onFailure = { kind, errorCode, elapsedMs ->
        recordRekognitionFailure(
          checkin,
          LogEntryType.OFFENDER_CHECKIN_FACE_MATCH_FAILED,
          attemptNotes(
            "result" to failureResultLabel(kind),
            "errorCode" to errorCode,
            "action" to "compare faces",
            "elapsedMs" to elapsedMs,
          ),
        )
      },
    )

    when (outcome.result) {
      AutomatedIdVerificationResult.MATCH -> {
        LOGGER.info("Facial recognition MATCH for checkin {} (similarity={})", checkin.uuid, outcome.topSimilarity)
      }
      AutomatedIdVerificationResult.NO_MATCH -> {
        LOGGER.info("Facial recognition NO_MATCH for checkin {} (topSimilarity={})", checkin.uuid, outcome.topSimilarity)
      }
      AutomatedIdVerificationResult.NO_FACE_DETECTED -> {
        LOGGER.warn("Facial recognition NO_FACE_DETECTED for checkin {}", checkin.uuid)
      }
      AutomatedIdVerificationResult.ERROR -> {
        LOGGER.error("Facial recognition ERROR for checkin {}", checkin.uuid)
      }
    }

    return outcome
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
    pageRequest: PageRequest,
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
    private val LOGGER = logger<CheckinV2Service>()
    private const val SYSTEM_PRACTITIONER = "system"
  }
}

data class CheckinReviewInfo(
  val newStatus: CheckinV2Status,
  val comment: String,
  val logEntryType: LogEntryType,
)

private enum class RekognitionFailureKind { TIMEOUT, REKOG_ERROR, OTHER }

/**
 * @return log entry comment appropriate for this checkin (depends on checkin status), and new status (possibly unchanged)
 * @throws ResponseStatusException on missing/blank comment or invalid checkin state
 */
fun ReviewCheckinV2Request.newValuesFor(status: CheckinV2Status): CheckinReviewInfo {
  var errorMessage: String? = null
  var newStatus: CheckinV2Status
  var logEntryType: LogEntryType
  val comment = when (status) {
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
    else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Can't review checkin withs status $status")
  } ?: ""
  if (status == CheckinV2Status.EXPIRED && comment.isBlank()) {
    throw ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage)
  }

  assert(status.canTransitionTo(newStatus))
  return CheckinReviewInfo(newStatus, comment, logEntryType)
}

object CheckinRequestApplicator {
  fun OffenderCheckinV2.applyRequest(request: SubmitCheckinV2Request, clock: Clock) {
    assert(this.status == CheckinV2Status.CREATED)
    this.surveyResponse = request.survey
    this.submittedAt = clock.instant()
    this.status = CheckinV2Status.SUBMITTED
  }

  fun OffenderCheckinV2.applyRequest(request: ReviewCheckinV2Request, reviewInfo: CheckinReviewInfo, clock: Clock) {
    assert(this.status == CheckinV2Status.SUBMITTED || this.status == CheckinV2Status.EXPIRED)
    this.status = reviewInfo.newStatus
    this.reviewedAt = clock.instant()
    this.reviewedBy = request.reviewedBy
    this.manualIdCheck = request.manualIdCheck
    this.riskFeedback = request.riskManagementFeedback
    this.sensitive = this.sensitive || request.sensitive
  }
}

private fun OffenderCheckinV2.toCheckinSubmittedEvent(
  contactDetails: ContactDetails?,
  clock: Clock,
  checkinWindow: Period,
  videoUrl: URL? = null,
  snapshotUrl: URL? = null,
) = CheckinSubmittedEvent(
  checkinId = this.id,
  offenderId = this.offender.id,
  checkin = this.dto(contactDetails, clock = clock, checkinWindow = checkinWindow, videoUrl = videoUrl, snapshotUrl = snapshotUrl),
  practitionerId = this.offender.practitionerId,
  offenderContactPreference = this.offender.contactPreference,
)

private fun OffenderCheckinV2.toCheckinReviewedEvent(
  contactDetails: ContactDetails?,
  clock: Clock,
  checkinWindow: Period,
  videoUrl: URL? = null,
  snapshotUrl: URL? = null,
) = CheckinReviewedEvent(
  checkinId = this.id,
  offenderId = this.offender.id,
  checkin = this.dto(contactDetails, clock = clock, checkinWindow = checkinWindow, videoUrl = videoUrl, snapshotUrl = snapshotUrl),
  practitionerId = this.offender.practitionerId,
  offenderContactPreference = this.offender.contactPreference,
)
