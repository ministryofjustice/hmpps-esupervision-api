package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import java.util.concurrent.CompletableFuture

/**
 * V2 Rekognition configuration - Stub for testing.
 * Creates a stub OffenderIdVerifier that always returns MATCH.
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

  companion object {
    private val LOGGER = LoggerFactory.getLogger(StubRekognitionV2Config::class.java)
  }
}

/**
 * Stub implementation for testing - always returns MATCH.
 * Only used in test/stubrekog profiles.
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
