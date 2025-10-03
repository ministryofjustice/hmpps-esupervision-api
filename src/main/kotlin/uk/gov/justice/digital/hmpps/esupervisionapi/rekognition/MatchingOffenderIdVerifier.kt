package uk.gov.justice.digital.hmpps.esupervisionapi.rekognition

import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedIdVerificationResult

/**
 * Identity verification service that always returns MATCH
 */
class MatchingOffenderIdVerifier : OffenderIdVerifier {
  override fun verifyCheckinImages(snapshots: CheckinVerificationImages, requiredConfidence: Float): AutomatedIdVerificationResult = AutomatedIdVerificationResult.MATCH
}
