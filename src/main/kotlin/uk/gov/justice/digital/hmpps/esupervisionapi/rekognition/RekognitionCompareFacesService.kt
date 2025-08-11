package uk.gov.justice.digital.hmpps.esupervisionapi.rekognition

import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.rekognition.RekognitionClient
import software.amazon.awssdk.services.rekognition.model.CompareFacesRequest
import software.amazon.awssdk.services.rekognition.model.Image
import software.amazon.awssdk.services.rekognition.model.InvalidParameterException
import software.amazon.awssdk.services.rekognition.model.RekognitionException
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedIdVerificationResult

class RekognitionCompareFacesService(
  val client: RekognitionClient,
) {
  fun verifyCheckinImages(snapshots: CheckinVerificationImages, requiredConfidence: Float): AutomatedIdVerificationResult {
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
