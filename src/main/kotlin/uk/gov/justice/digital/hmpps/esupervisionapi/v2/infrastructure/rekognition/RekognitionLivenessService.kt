package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.rekognition.RekognitionAsyncClient
import software.amazon.awssdk.services.rekognition.model.ChallengePreference
import software.amazon.awssdk.services.rekognition.model.CreateFaceLivenessSessionRequest
import software.amazon.awssdk.services.rekognition.model.CreateFaceLivenessSessionRequestSettings
import software.amazon.awssdk.services.rekognition.model.GetFaceLivenessSessionResultsRequest
import software.amazon.awssdk.services.rekognition.model.GetFaceLivenessSessionResultsResponse
import java.util.concurrent.CompletableFuture

/**
 * Service for managing AWS Rekognition Face Liveness sessions.
 *
 * Creates liveness sessions for the browser-based FaceLivenessDetector,
 * and retrieves session results including liveness confidence and reference image.
 */
interface LivenessSessionService {
  fun createSession(): CompletableFuture<String>
  fun getSessionResults(sessionId: String): CompletableFuture<GetFaceLivenessSessionResultsResponse>
}

open class RekognitionLivenessService(
  private val asyncClient: RekognitionAsyncClient,
) : LivenessSessionService {

  @CircuitBreaker(name = "awsRekognition")
  @Retry(name = "awsRekognition")
  override fun createSession(): CompletableFuture<String> {
    LOGGER.info("Creating Rekognition Face Liveness session")

    val movementOnly = ChallengePreference.builder().type("FaceMovementChallenge").build()
    val movementAndLight = ChallengePreference.builder().type("FaceMovementAndLightChallenge").build()

    val settings = CreateFaceLivenessSessionRequestSettings.builder()
      .challengePreferences(movementOnly)
      .auditImagesLimit(4)
      .build()

    val request = CreateFaceLivenessSessionRequest.builder().settings(settings).build()

    return asyncClient.createFaceLivenessSession(request)
      .thenApply { response ->
        LOGGER.info("Liveness session created: {}", response.sessionId())
        response.sessionId()
      }
  }

  @CircuitBreaker(name = "awsRekognition")
  @Retry(name = "awsRekognition")
  override fun getSessionResults(sessionId: String): CompletableFuture<GetFaceLivenessSessionResultsResponse> {
    LOGGER.info("Getting liveness session results for session: {}", sessionId)

    val request = GetFaceLivenessSessionResultsRequest.builder()
      .sessionId(sessionId)
      .build()

    return asyncClient.getFaceLivenessSessionResults(request)
      .thenApply { response ->
        LOGGER.info(
          "Liveness session {} result: confidence={}, status={}",
          sessionId,
          response.confidence(),
          response.statusAsString(),
        )
        response
      }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(RekognitionLivenessService::class.java)
  }
}
