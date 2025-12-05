package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition

import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult

/**
 * Identity verification service that always returns MATCH
 */
@Service
class MatchingOffenderIdVerifier : OffenderIdVerifier {
  override fun verifyCheckinImages(snapshots: CheckinVerificationImages, requiredConfidence: Float): AutomatedIdVerificationResult = AutomatedIdVerificationResult.MATCH
}
