package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.rekognition.RekognitionClient
import software.amazon.awssdk.services.rekognition.model.CompareFacesRequest
import software.amazon.awssdk.services.rekognition.model.Image
import software.amazon.awssdk.services.rekognition.model.InvalidParameterException
import software.amazon.awssdk.services.rekognition.model.RekognitionException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult

interface OffenderIdVerifier {
  fun verifyCheckinImages(snapshots: CheckinVerificationImages, requiredConfidence: Float): AutomatedIdVerificationResult
}

class RekognitionCompareFacesService(
  val client: RekognitionClient,
) : OffenderIdVerifier {
  @CircuitBreaker(name = "awsRekognition")
  @Retry(name = "awsRekognition")
  override fun verifyCheckinImages(snapshots: CheckinVerificationImages, requiredConfidence: Float): AutomatedIdVerificationResult {
    for (snapshot in snapshots.snapshots) {
      val matches = compareFaces(snapshots.reference, snapshot, requiredConfidence)
      if (matches) {
        return AutomatedIdVerificationResult.MATCH
      }
    }

    return AutomatedIdVerificationResult.NO_MATCH
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

      // Debug logging for Rekognition response
      LOGGER.debug(
        "Rekognition response - sourceImageFace: boundingBox={}, confidence={}",
        result.sourceImageFace()?.boundingBox(),
        result.sourceImageFace()?.confidence(),
      )

      LOGGER.debug(
        "Rekognition response - faceMatches count: {}, unmatchedFaces count: {}",
        result.faceMatches().size,
        result.unmatchedFaces().size,
      )

      result.faceMatches().forEachIndexed { index, match ->
        LOGGER.debug(
          "Rekognition faceMatch[{}] - similarity: {}, boundingBox: {}, confidence: {}",
          index,
          match.similarity(),
          match.face()?.boundingBox(),
          match.face()?.confidence(),
        )
      }

      result.unmatchedFaces().forEachIndexed { index, face ->
        LOGGER.debug(
          "Rekognition unmatchedFace[{}] - boundingBox: {}, confidence: {}",
          index,
          face.boundingBox(),
          face.confidence(),
        )
      }

      val isMatch = result.faceMatches().isNotEmpty()
      LOGGER.info(
        "Facial comparison result: isMatch={}, faceMatchesCount={}, unmatchedFacesCount={}, topSimilarity={}",
        isMatch,
        result.faceMatches().size,
        result.unmatchedFaces().size,
        result.faceMatches().maxOfOrNull { it.similarity() },
      )

      return isMatch
    } catch (ex: InvalidParameterException) {
      // Rekognition service could not find any faces in photo
      LOGGER.warn(
        "Rekognition InvalidParameterException (no face detected) for comparison {} vs {} - message: {}",
        referenceCoord.key,
        comparisonCoord.key,
        ex.message,
      )
      return false
    } catch (ex: RekognitionException) {
      LOGGER.warn("Rekognition service error: {}", ex.message)
      throw ex
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
