package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition

import software.amazon.awssdk.services.rekognition.model.S3Object

data class S3ObjectCoordinate(
  val bucket: String,
  val key: String,
) {
  fun toS3Object(): S3Object = S3Object.builder()
    .bucket(bucket)
    .name(key)
    .build()
}
