package uk.gov.justice.digital.hmpps.esupervisionapi.rekognition

import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.rekognition.RekognitionAsyncClient
import software.amazon.awssdk.services.rekognition.RekognitionClient
import software.amazon.awssdk.services.rekognition.model.CompareFacesRequest
import software.amazon.awssdk.services.rekognition.model.Image
import software.amazon.awssdk.services.rekognition.model.InvalidParameterException
import software.amazon.awssdk.services.rekognition.model.RekognitionException
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedIdVerificationResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

interface OffenderIdVerifier {
  fun verifyCheckinImagesAsync(snapshots: CheckinVerificationImages, requiredConfidence: Float): CompletableFuture<AutomatedIdVerificationResult>
}

class RekognitionCompareFacesService(
  val client: RekognitionClient,
  val asyncClient: RekognitionAsyncClient,
) : OffenderIdVerifier {

  override fun verifyCheckinImagesAsync(snapshots: CheckinVerificationImages, requiredConfidence: Float): CompletableFuture<AutomatedIdVerificationResult> {
    if (snapshots.snapshots.isEmpty()) {
      return CompletableFuture.completedFuture(AutomatedIdVerificationResult.NO_MATCH)
    }

    val snapshotRequests = snapshots.snapshots.map {
      compareFacesAsync(snapshots.reference, it, requiredConfidence)
    }
    val result = CompletableFuture.allOf(*snapshotRequests.toTypedArray()).thenApply {
      snapshotRequests.map { it.get() }.any()
    }.thenApply {
      when (it) {
        true -> AutomatedIdVerificationResult.MATCH
        else -> AutomatedIdVerificationResult.NO_MATCH
      }
    }
    return result
  }

  private fun compareFaces(referenceCoord: S3ObjectCoordinate, comparisonCoord: S3ObjectCoordinate, requiredConfidence: Float): Boolean {
    val reference = toImage(referenceCoord)
    val comparison = toImage(comparisonCoord)

    val compareRequest = CompareFacesRequest.builder()
      .sourceImage(reference)
      .targetImage(comparison)
      .similarityThreshold(requiredConfidence)
      .build()

    try {
      LOGGER.info("Submitting facial comparison between ${referenceCoord.key} and ${comparisonCoord.key}")

      val result = client.compareFaces(compareRequest)

      val isMatch = result.faceMatches().isNotEmpty()
      LOGGER.info("Match result: $isMatch")

      return isMatch
    } catch (ex: InvalidParameterException) {
      // Rekognition service could not find any faces in photo
      return false
    } catch (ex: RekognitionException) {
      LOGGER.warn("Rekognition service error: {}", ex.message)
      throw ex
    }
  }

  private fun compareFacesAsync(referenceCoord: S3ObjectCoordinate, comparisonCoord: S3ObjectCoordinate, requiredConfidence: Float): CompletableFuture<Boolean> {
    val reference = toImage(referenceCoord)
    val comparison = toImage(comparisonCoord)

    LOGGER.info("Submitting facial comparison between ${referenceCoord.key} and ${comparisonCoord.key} (async)")

    val future = asyncClient.compareFaces { b ->
      b.sourceImage(reference)
        .targetImage(comparison)
        .similarityThreshold(requiredConfidence)
    }

    return future.handle { response, throwable ->
      if (throwable != null) {
        val cause = if (throwable is CompletionException) throwable.cause ?: throwable else throwable
        when (cause) {
          is InvalidParameterException -> false // no face detected -> treat as no match
          is RekognitionException -> throw cause
          else -> throw CompletionException(cause)
        }
      } else {
        val isMatch = response.faceMatches().isNotEmpty()
        LOGGER.info("Match result (async): $isMatch")
        isMatch
      }
    }
  }

  companion object {
    val LOGGER = LoggerFactory.getLogger(this::class.java)

    fun toImage(objectCoord: S3ObjectCoordinate): Image {
      val obj = objectCoord.toS3Object()
      return Image.builder()
        .s3Object(obj)
        .build()
    }
  }
}
