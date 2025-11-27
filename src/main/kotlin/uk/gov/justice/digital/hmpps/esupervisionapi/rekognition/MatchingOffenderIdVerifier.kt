package uk.gov.justice.digital.hmpps.esupervisionapi.rekognition

import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedIdVerificationResult
import java.util.concurrent.CompletableFuture

/**
 * Identity verification service that always returns MATCH
 */
class MatchingOffenderIdVerifier : OffenderIdVerifier {
  override fun verifyCheckinImagesAsync(snapshots: CheckinVerificationImages, requiredConfidence: Float): CompletableFuture<AutomatedIdVerificationResult> = CompletableFuture.completedFuture(AutomatedIdVerificationResult.MATCH)
}
