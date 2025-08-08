package uk.gov.justice.digital.hmpps.esupervisionapi.rekognition

data class CheckinVerificationImages(
  val reference: S3ObjectCoordinate,
  val snapshots: List<S3ObjectCoordinate>,
)
