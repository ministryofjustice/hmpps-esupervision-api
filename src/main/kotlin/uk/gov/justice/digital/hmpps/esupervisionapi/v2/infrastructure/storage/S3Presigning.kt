package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage

import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URL
import java.time.Duration

/** A presigned URL together with the headers a client must echo on the PUT. */
data class PresignedUpload(
  val url: URL,
  val requiredHeaders: Map<String, String>,
)

internal fun S3Presigner.presignGet(bucket: String, key: String, duration: Duration): URL = presignGetObject(
  GetObjectPresignRequest.builder()
    .signatureDuration(duration)
    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
    .build(),
).url()

internal fun S3Presigner.presignPut(
  bucket: String,
  key: String,
  contentType: String,
  duration: Duration,
  checksumSha256Base64: String?,
): PresignedUpload {
  val putRequest = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
  if (checksumSha256Base64 != null) {
    putRequest.checksumSHA256(checksumSha256Base64)
  }
  val url = presignPutObject(
    PutObjectPresignRequest.builder()
      .putObjectRequest(putRequest.build())
      .signatureDuration(duration)
      .build(),
  ).url()
  val headers = if (checksumSha256Base64 != null) {
    mapOf("x-amz-checksum-sha256" to checksumSha256Base64)
  } else {
    emptyMap()
  }
  return PresignedUpload(url, headers)
}
