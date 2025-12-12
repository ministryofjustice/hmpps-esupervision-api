package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.rekognition.RekognitionAsyncClient
import software.amazon.awssdk.services.rekognition.model.BoundingBox
import software.amazon.awssdk.services.rekognition.model.CompareFacesMatch
import software.amazon.awssdk.services.rekognition.model.CompareFacesResponse
import software.amazon.awssdk.services.rekognition.model.ComparedFace
import software.amazon.awssdk.services.rekognition.model.ComparedSourceImageFace
import software.amazon.awssdk.services.rekognition.model.InvalidParameterException
import software.amazon.awssdk.services.rekognition.model.RekognitionException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class RekognitionCompareFacesServiceTest {

  private val asyncClient: RekognitionAsyncClient = mock()
  private lateinit var service: RekognitionCompareFacesService

  @BeforeEach
  fun setUp() {
    service = RekognitionCompareFacesService(asyncClient)
  }

  @Test
  fun `verifyCheckinImages - returns MATCH when faces match`() {
    val images = createTestImages(snapshotCount = 1)
    val response = createMatchResponse(similarity = 95.0f)

    whenever(asyncClient.compareFaces(any<Consumer<software.amazon.awssdk.services.rekognition.model.CompareFacesRequest.Builder>>()))
      .thenReturn(CompletableFuture.completedFuture(response))

    val result = service.verifyCheckinImages(images, 80.0f).join()

    assertEquals(AutomatedIdVerificationResult.MATCH, result)
  }

  @Test
  fun `verifyCheckinImages - returns NO_MATCH when faces do not match`() {
    val images = createTestImages(snapshotCount = 1)
    val response = createNoMatchResponse()

    whenever(asyncClient.compareFaces(any<Consumer<software.amazon.awssdk.services.rekognition.model.CompareFacesRequest.Builder>>()))
      .thenReturn(CompletableFuture.completedFuture(response))

    val result = service.verifyCheckinImages(images, 80.0f).join()

    assertEquals(AutomatedIdVerificationResult.NO_MATCH, result)
  }

  @Test
  fun `verifyCheckinImages - returns NO_FACE_DETECTED when InvalidParameterException thrown`() {
    val images = createTestImages(snapshotCount = 1)
    val exception = InvalidParameterException.builder()
      .message("No face detected in source image")
      .build()

    whenever(asyncClient.compareFaces(any<Consumer<software.amazon.awssdk.services.rekognition.model.CompareFacesRequest.Builder>>()))
      .thenReturn(CompletableFuture.failedFuture(exception))

    val result = service.verifyCheckinImages(images, 80.0f).join()

    assertEquals(AutomatedIdVerificationResult.NO_FACE_DETECTED, result)
  }

  @Test
  fun `verifyCheckinImages - returns ERROR when RekognitionException thrown`() {
    val images = createTestImages(snapshotCount = 1)
    val exception = RekognitionException.builder()
      .message("Service unavailable")
      .build()

    whenever(asyncClient.compareFaces(any<Consumer<software.amazon.awssdk.services.rekognition.model.CompareFacesRequest.Builder>>()))
      .thenReturn(CompletableFuture.failedFuture(exception))

    val result = service.verifyCheckinImages(images, 80.0f).join()

    assertEquals(AutomatedIdVerificationResult.ERROR, result)
  }

  @Test
  fun `verifyCheckinImages - returns NO_MATCH when no snapshots provided`() {
    val images = CheckinVerificationImages(
      reference = S3ObjectCoordinate("bucket", "reference.jpg"),
      snapshots = emptyList(),
    )

    val result = service.verifyCheckinImages(images, 80.0f).join()

    assertEquals(AutomatedIdVerificationResult.NO_MATCH, result)
  }

  @Test
  fun `verifyCheckinImages - returns MATCH when at least one snapshot matches`() {
    val images = createTestImages(snapshotCount = 3)
    val noMatchResponse = createNoMatchResponse()
    val matchResponse = createMatchResponse(similarity = 92.0f)

    // First call returns no match, second returns match, third returns no match
    whenever(asyncClient.compareFaces(any<Consumer<software.amazon.awssdk.services.rekognition.model.CompareFacesRequest.Builder>>()))
      .thenReturn(CompletableFuture.completedFuture(noMatchResponse))
      .thenReturn(CompletableFuture.completedFuture(matchResponse))
      .thenReturn(CompletableFuture.completedFuture(noMatchResponse))

    val result = service.verifyCheckinImages(images, 80.0f).join()

    assertEquals(AutomatedIdVerificationResult.MATCH, result)
  }

  @Test
  fun `verifyCheckinImages - returns NO_FACE_DETECTED when all snapshots have no face`() {
    val images = createTestImages(snapshotCount = 2)
    val exception = InvalidParameterException.builder()
      .message("No face detected")
      .build()

    whenever(asyncClient.compareFaces(any<Consumer<software.amazon.awssdk.services.rekognition.model.CompareFacesRequest.Builder>>()))
      .thenReturn(CompletableFuture.failedFuture(exception))

    val result = service.verifyCheckinImages(images, 80.0f).join()

    assertEquals(AutomatedIdVerificationResult.NO_FACE_DETECTED, result)
  }

  @Test
  fun `verifyCheckinImages - returns NO_MATCH when some have no face but others have non-matching faces`() {
    val images = createTestImages(snapshotCount = 2)
    val noFaceException = InvalidParameterException.builder()
      .message("No face detected")
      .build()
    val noMatchResponse = createNoMatchResponse()

    // First snapshot: no face, second snapshot: face but no match
    whenever(asyncClient.compareFaces(any<Consumer<software.amazon.awssdk.services.rekognition.model.CompareFacesRequest.Builder>>()))
      .thenReturn(CompletableFuture.failedFuture(noFaceException))
      .thenReturn(CompletableFuture.completedFuture(noMatchResponse))

    val result = service.verifyCheckinImages(images, 80.0f).join()

    assertEquals(AutomatedIdVerificationResult.NO_MATCH, result)
  }

  private fun createTestImages(snapshotCount: Int) = CheckinVerificationImages(
    reference = S3ObjectCoordinate("test-bucket", "offenders/123/setup-photo.jpg"),
    snapshots = (0 until snapshotCount).map { index ->
      S3ObjectCoordinate("test-bucket", "checkins/456/snapshot-$index.jpg")
    },
  )

  private fun createMatchResponse(similarity: Float): CompareFacesResponse {
    val boundingBox = BoundingBox.builder()
      .left(0.1f)
      .top(0.1f)
      .width(0.5f)
      .height(0.5f)
      .build()

    val sourceImageFace = ComparedSourceImageFace.builder()
      .boundingBox(boundingBox)
      .confidence(99.0f)
      .build()

    val matchedFace = ComparedFace.builder()
      .boundingBox(boundingBox)
      .confidence(98.0f)
      .build()

    val faceMatch = CompareFacesMatch.builder()
      .similarity(similarity)
      .face(matchedFace)
      .build()

    return CompareFacesResponse.builder()
      .sourceImageFace(sourceImageFace)
      .faceMatches(listOf(faceMatch))
      .unmatchedFaces(emptyList())
      .build()
  }

  private fun createNoMatchResponse(): CompareFacesResponse {
    val boundingBox = BoundingBox.builder()
      .left(0.1f)
      .top(0.1f)
      .width(0.5f)
      .height(0.5f)
      .build()

    val sourceImageFace = ComparedSourceImageFace.builder()
      .boundingBox(boundingBox)
      .confidence(99.0f)
      .build()

    val unmatchedFace = ComparedFace.builder()
      .boundingBox(boundingBox)
      .confidence(95.0f)
      .build()

    return CompareFacesResponse.builder()
      .sourceImageFace(sourceImageFace)
      .faceMatches(emptyList())
      .unmatchedFaces(listOf(unmatchedFace))
      .build()
  }
}
