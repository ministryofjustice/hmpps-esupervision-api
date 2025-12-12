package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.rekognition.RekognitionAsyncClient
import software.amazon.awssdk.services.rekognition.model.CompareFacesResponse
import software.amazon.awssdk.services.rekognition.model.Image
import software.amazon.awssdk.services.rekognition.model.InvalidParameterException
import software.amazon.awssdk.services.rekognition.model.RekognitionException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

interface OffenderIdVerifier {
  fun verifyCheckinImages(snapshots: CheckinVerificationImages, requiredConfidence: Float): CompletableFuture<AutomatedIdVerificationResult>
}

/**
 * AWS Rekognition facial comparison service.
 *
 * Features:
 * - Async parallel processing of multiple snapshots
 * - Circuit breaker and retry for resilience
 * - Detailed debug logging of Rekognition response
 * - Distinguishes between NO_MATCH vs NO_FACE_DETECTED
 */
open class RekognitionCompareFacesService(
  private val asyncClient: RekognitionAsyncClient,
) : OffenderIdVerifier {

  @CircuitBreaker(name = "awsRekognition")
  @Retry(name = "awsRekognition")
  override fun verifyCheckinImages(
    snapshots: CheckinVerificationImages,
    requiredConfidence: Float,
  ): CompletableFuture<AutomatedIdVerificationResult> {
    if (snapshots.snapshots.isEmpty()) {
      LOGGER.warn("No snapshots provided for facial verification")
      return CompletableFuture.completedFuture(AutomatedIdVerificationResult.NO_MATCH)
    }

    LOGGER.info(
      "Starting facial verification: reference={}, snapshotCount={}, threshold={}",
      snapshots.reference.key,
      snapshots.snapshots.size,
      requiredConfidence,
    )

    // Launch all comparisons in parallel
    val snapshotFutures = snapshots.snapshots.mapIndexed { index, snapshot ->
      compareFacesAsync(snapshots.reference, snapshot, requiredConfidence, index)
    }

    // Wait for all to complete and determine result
    return CompletableFuture.allOf(*snapshotFutures.toTypedArray())
      .thenApply {
        val results = snapshotFutures.map { it.join() }

        // Check if any matched
        val matchResult = results.find { it.result == AutomatedIdVerificationResult.MATCH }
        if (matchResult != null) {
          LOGGER.info(
            "Facial verification MATCH found at snapshot index {}, similarity={}",
            matchResult.snapshotIndex,
            matchResult.topSimilarity,
          )
          return@thenApply AutomatedIdVerificationResult.MATCH
        }

        // Check if all failed due to no face detected
        val allNoFace = results.all { it.result == AutomatedIdVerificationResult.NO_FACE_DETECTED }
        if (allNoFace) {
          LOGGER.warn("Facial verification failed: NO_FACE_DETECTED in any snapshot")
          return@thenApply AutomatedIdVerificationResult.NO_FACE_DETECTED
        }

        // Check if any had errors
        val errorResult = results.find { it.result == AutomatedIdVerificationResult.ERROR }
        if (errorResult != null) {
          LOGGER.error("Facial verification failed with ERROR at snapshot index {}", errorResult.snapshotIndex)
          return@thenApply AutomatedIdVerificationResult.ERROR
        }

        // Otherwise it's a NO_MATCH (faces found but didn't match)
        val topSimilarity = results.mapNotNull { it.topSimilarity }.maxOrNull()
        LOGGER.info(
          "Facial verification NO_MATCH: faces detected but similarity below threshold, topSimilarity={}",
          topSimilarity,
        )
        AutomatedIdVerificationResult.NO_MATCH
      }
  }

  private fun compareFacesAsync(
    referenceCoord: S3ObjectCoordinate,
    comparisonCoord: S3ObjectCoordinate,
    requiredConfidence: Float,
    snapshotIndex: Int,
  ): CompletableFuture<ComparisonResult> {
    val reference = toImage(referenceCoord)
    val comparison = toImage(comparisonCoord)

    LOGGER.info(
      "Submitting facial comparison [{}]: reference={}, snapshot={}",
      snapshotIndex,
      referenceCoord.key,
      comparisonCoord.key,
    )

    val future = asyncClient.compareFaces { builder ->
      builder
        .sourceImage(reference)
        .targetImage(comparison)
        .similarityThreshold(requiredConfidence)
    }

    return future.handle { response, throwable ->
      if (throwable != null) {
        handleComparisonError(throwable, referenceCoord, comparisonCoord, snapshotIndex)
      } else {
        handleComparisonSuccess(response, snapshotIndex)
      }
    }
  }

  private fun handleComparisonSuccess(response: CompareFacesResponse, snapshotIndex: Int): ComparisonResult {
    // Debug logging for Rekognition response
    LOGGER.debug(
      "Rekognition response [{}] - sourceImageFace: boundingBox={}, confidence={}",
      snapshotIndex,
      response.sourceImageFace()?.boundingBox(),
      response.sourceImageFace()?.confidence(),
    )

    LOGGER.debug(
      "Rekognition response [{}] - faceMatches={}, unmatchedFaces={}",
      snapshotIndex,
      response.faceMatches().size,
      response.unmatchedFaces().size,
    )

    response.faceMatches().forEachIndexed { matchIndex, match ->
      LOGGER.debug(
        "Rekognition faceMatch[{}][{}] - similarity={}, boundingBox={}, confidence={}",
        snapshotIndex,
        matchIndex,
        match.similarity(),
        match.face()?.boundingBox(),
        match.face()?.confidence(),
      )
    }

    response.unmatchedFaces().forEachIndexed { faceIndex, face ->
      LOGGER.debug(
        "Rekognition unmatchedFace[{}][{}] - boundingBox={}, confidence={}",
        snapshotIndex,
        faceIndex,
        face.boundingBox(),
        face.confidence(),
      )
    }

    val isMatch = response.faceMatches().isNotEmpty()
    val topSimilarity = response.faceMatches().maxOfOrNull { it.similarity() }

    LOGGER.info(
      "Facial comparison result [{}]: isMatch={}, faceMatches={}, unmatchedFaces={}, topSimilarity={}",
      snapshotIndex,
      isMatch,
      response.faceMatches().size,
      response.unmatchedFaces().size,
      topSimilarity,
    )

    return ComparisonResult(
      snapshotIndex = snapshotIndex,
      result = if (isMatch) AutomatedIdVerificationResult.MATCH else AutomatedIdVerificationResult.NO_MATCH,
      topSimilarity = topSimilarity,
    )
  }

  private fun handleComparisonError(
    throwable: Throwable,
    referenceCoord: S3ObjectCoordinate,
    comparisonCoord: S3ObjectCoordinate,
    snapshotIndex: Int,
  ): ComparisonResult {
    val cause = if (throwable is CompletionException) throwable.cause ?: throwable else throwable

    return when (cause) {
      is InvalidParameterException -> {
        // Rekognition could not find any faces in the photo
        LOGGER.warn(
          "Rekognition NO_FACE_DETECTED [{}]: reference={}, snapshot={}, message={}",
          snapshotIndex,
          referenceCoord.key,
          comparisonCoord.key,
          cause.message,
        )
        ComparisonResult(
          snapshotIndex = snapshotIndex,
          result = AutomatedIdVerificationResult.NO_FACE_DETECTED,
          topSimilarity = null,
        )
      }
      is RekognitionException -> {
        LOGGER.error(
          "Rekognition ERROR [{}]: reference={}, snapshot={}, message={}",
          snapshotIndex,
          referenceCoord.key,
          comparisonCoord.key,
          cause.message,
        )
        ComparisonResult(
          snapshotIndex = snapshotIndex,
          result = AutomatedIdVerificationResult.ERROR,
          topSimilarity = null,
        )
      }
      else -> {
        LOGGER.error(
          "Unexpected error during facial comparison [{}]: reference={}, snapshot={}",
          snapshotIndex,
          referenceCoord.key,
          comparisonCoord.key,
          cause,
        )
        throw CompletionException(cause)
      }
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(RekognitionCompareFacesService::class.java)

    private fun toImage(objectCoord: S3ObjectCoordinate): Image {
      val obj = objectCoord.toS3Object()
      return Image.builder()
        .s3Object(obj)
        .build()
    }
  }
}

/**
 * Internal result from a single snapshot comparison
 */
private data class ComparisonResult(
  val snapshotIndex: Int,
  val result: AutomatedIdVerificationResult,
  val topSimilarity: Float?,
)
