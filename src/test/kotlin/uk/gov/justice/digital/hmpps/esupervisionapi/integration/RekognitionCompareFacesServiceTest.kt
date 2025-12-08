package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.rekognition.RekognitionAsyncClient
import software.amazon.awssdk.services.rekognition.RekognitionClient
import software.amazon.awssdk.services.rekognition.model.CompareFacesResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.CheckinVerificationImages
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.RekognitionCompareFacesService
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.S3ObjectCoordinate
import java.util.concurrent.CompletableFuture

class RekognitionCompareFacesServiceTest {

  val client: RekognitionClient = mock()
  val asyncClient: RekognitionAsyncClient = mock()
  val response: CompareFacesResponse = mock()

  @BeforeEach
  fun setUp() {
    reset(client, asyncClient, response)
  }

  @Test
  fun `test no matches are handled correctly`() {
    whenever(response.faceMatches()).thenReturn(emptyList())
    whenever(asyncClient.compareFaces(any<java.util.function.Consumer<software.amazon.awssdk.services.rekognition.model.CompareFacesRequest.Builder>>()))
      .thenReturn(CompletableFuture.completedFuture(response))

    val service = RekognitionCompareFacesService(client, asyncClient)
    val result = service.verifyCheckinImagesAsync(
      CheckinVerificationImages(
        S3ObjectCoordinate("foo", "reference-image"),
        listOf(S3ObjectCoordinate("foo", "checkin-image")),
      ),
      0.9f,
    )

    Assertions.assertEquals(AutomatedIdVerificationResult.NO_MATCH, result.get())
  }

  @Test
  fun `test matches are handled correctly`() {
    val response: CompareFacesResponse = mock()
    whenever(response.faceMatches()).thenReturn(listOf(mock()))
    whenever(asyncClient.compareFaces(any<java.util.function.Consumer<software.amazon.awssdk.services.rekognition.model.CompareFacesRequest.Builder>>()))
      .thenReturn(CompletableFuture.completedFuture(response))

    val service = RekognitionCompareFacesService(client, asyncClient)
    val result = service.verifyCheckinImagesAsync(
      CheckinVerificationImages(
        S3ObjectCoordinate("foo", "reference-image"),
        listOf(S3ObjectCoordinate("foo", "checkin-image")),
      ),
      0.9f,
    )

    Assertions.assertEquals(AutomatedIdVerificationResult.MATCH, result.get())
  }
}
