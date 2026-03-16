package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.rekognition.model.AuditImage
import software.amazon.awssdk.services.rekognition.model.GetFaceLivenessSessionResultsResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import java.util.concurrent.CompletableFuture

/**
 * V2 Rekognition configuration - Stub for testing.
 * Creates stub beans that return canned responses.
 * Only active in test or stubrekog profiles.
 */
@Profile("test | stubrekog")
@Configuration
class StubRekognitionV2Config {

  @Bean
  fun offenderIdVerifierV2(): OffenderIdVerifier {
    LOGGER.info("Creating V2 STUB OffenderIdVerifier (always returns MATCH)")
    return StubOffenderIdVerifier()
  }

  @Bean
  fun livenessSessionServiceV2(): LivenessSessionService {
    LOGGER.info("Creating V2 STUB LivenessSessionService")
    return StubLivenessSessionService()
  }

  @Bean
  fun livenessCredentialsService(): LivenessCredentialsProvider {
    LOGGER.info("Creating STUB LivenessCredentialsProvider")
    return StubLivenessCredentialsProvider()
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(StubRekognitionV2Config::class.java)
  }
}

/**
 * Stub implementation for testing - always returns MATCH.
 */
class StubOffenderIdVerifier : OffenderIdVerifier {
  override fun verifyCheckinImages(
    snapshots: CheckinVerificationImages,
    requiredConfidence: Float,
  ): CompletableFuture<AutomatedIdVerificationResult> {
    LOGGER.info(
      "STUB: Returning MATCH for facial verification (reference={}, snapshots={})",
      snapshots.reference.key,
      snapshots.snapshots.size,
    )
    return CompletableFuture.completedFuture(AutomatedIdVerificationResult.MATCH)
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(StubOffenderIdVerifier::class.java)
  }
}

/**
 * Stub liveness session service - returns a fake session ID and high confidence results.
 */
class StubLivenessSessionService : LivenessSessionService {
  override fun createSession(): CompletableFuture<String> {
    LOGGER.info("STUB: Creating fake liveness session")
    return CompletableFuture.completedFuture("stub-liveness-session-id")
  }

  override fun getSessionResults(sessionId: String): CompletableFuture<GetFaceLivenessSessionResultsResponse> {
    LOGGER.info("STUB: Returning fake liveness results for session {}", sessionId)
    // 1x1 red pixel JPEG
    val stubImageBytes = byteArrayOf(
      0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
      0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
      0x00, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte(),
    )
    val referenceImage = AuditImage.builder()
      .bytes(SdkBytes.fromByteArray(stubImageBytes))
      .build()
    val response = GetFaceLivenessSessionResultsResponse.builder()
      .sessionId(sessionId)
      .confidence(99.5f)
      .status("SUCCEEDED")
      .referenceImage(referenceImage)
      .build()
    return CompletableFuture.completedFuture(response)
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(StubLivenessSessionService::class.java)
  }
}

/**
 * Stub credentials provider - returns fake credentials for testing.
 */
class StubLivenessCredentialsProvider : LivenessCredentialsProvider {
  override fun getCredentials(): LivenessCredentialsResponse {
    LOGGER.info("STUB: Returning fake liveness credentials")
    return LivenessCredentialsResponse(
      accessKeyId = "stub-access-key",
      secretAccessKey = "stub-secret-key",
      sessionToken = "stub-session-token",
      expiration = "2099-12-31T23:59:59Z",
    )
  }

  override fun getRegion(): String = "eu-west-2"

  companion object {
    private val LOGGER = LoggerFactory.getLogger(StubLivenessCredentialsProvider::class.java)
  }
}
