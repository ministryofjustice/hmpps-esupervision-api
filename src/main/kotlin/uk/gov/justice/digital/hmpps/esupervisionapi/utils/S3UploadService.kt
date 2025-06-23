package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetup
import java.net.URL
import java.time.Duration
import java.util.UUID

/**
 * Ensure we're being consistent with object keys in S3
 */
data class SetupPhotoKey(
  val invite: UUID,
) {
  fun asString(): String = "invite-$invite"
}

/**
 * Ensure we're being consistent with object keys in S3
 */
data class CheckinVideoKey(
  val checkin: UUID,
) {
  fun asString(): String = "checkin-$checkin"
}

@Service
class S3UploadService(
  val s3uploadClient: S3Client,
  val s3Presigner: S3Presigner,
  @Value("\${aws.s3.image-uploads}") private val imageUploadBucket: String,
  @Value("\${aws.s3.video-uploads}") private val videoUploadBucket: String,
) {

  private fun putObjectRequest(bucket: String, key: String, contentType: String): PutObjectRequest {
    val request = PutObjectRequest.builder()
      .bucket(bucket)
      .key(key)
      .contentType(contentType)
      .build()
    return request
  }

  internal fun putObjectRequest(setup: OffenderSetup, contentType: String): PutObjectRequest = putObjectRequest(imageUploadBucket, SetupPhotoKey(setup.offender.uuid).asString(), contentType)

  internal fun putObjectRequest(checkin: OffenderCheckin, contentType: String): PutObjectRequest = putObjectRequest(videoUploadBucket, CheckinVideoKey(checkin.uuid).asString(), contentType)

  /**
   * Generates a pre-signed URL for uploading a file to S3.
   * @param setup The offender invite.
   * @param contentType content type of the file
   * @param duration The expiration time for the pre-signed URL in minutes.
   * @return A pre-signed URL for uploading the file.
   */
  fun generatePresignedUploadUrl(
    setup: OffenderSetup,
    contentType: String = "application/octet-stream",
    duration: Duration,
  ): URL {
    val putRequest = putObjectRequest(setup, contentType)
    val presignRequest = PutObjectPresignRequest.builder()
      .putObjectRequest(putRequest)
      .signatureDuration(duration)
      .build()

    return s3Presigner.presignPutObject(presignRequest).url()
  }

  /**
   * Generates a pre-signed URL for uploading a file to S3.
   * @param cehckin The offender checkin
   * @param contentType content type of the file
   * @param duration The expiration time for the pre-signed URL in minutes.
   * @return A pre-signed URL for uploading the file.
   */
  fun generatePresignedUploadUrl(
    checkin: OffenderCheckin,
    contentType: String = "application/octet-stream",
    duration: Duration,
  ): URL {
    val putRequest = putObjectRequest(checkin, contentType)
    val presignRequest = PutObjectPresignRequest.builder()
      .putObjectRequest(putRequest)
      .signatureDuration(duration)
      .build()

    return s3Presigner.presignPutObject(presignRequest).url()
  }

  fun isSetupPhotoUploaded(setup: OffenderSetup): Boolean {
    val request = HeadObjectRequest.builder()
      .bucket(imageUploadBucket)
      .key(SetupPhotoKey(setup.offender.uuid).asString())
      .build()
    return isObjectUploaded(request)
  }

  fun isCheckinVideoUploaded(checkin: OffenderCheckin): Boolean {
    val request = HeadObjectRequest.builder()
      .bucket(videoUploadBucket)
      .key(CheckinVideoKey(checkin.uuid).asString())
      .build()
    return isObjectUploaded(request)
  }

  private fun isObjectUploaded(request: HeadObjectRequest): Boolean {
    try {
      s3uploadClient.headObject(request)
      return true
    } catch (e: NoSuchKeyException) {
      return false
    }
  }
}
