package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderStatus
import java.net.URL
import java.time.Duration
import java.util.UUID

sealed class S3Keyable {
  fun toKey(): String {
    when (this) {
      is SetupPhotoKey -> {
        return "setup-${this.invite}"
      }
      is CheckinVideoKey -> {
        return "checkin-${this.checkin}/video"
      }
      is CheckinPhotoKey -> {
        return "checkin-${this.checkin}/${this.index}"
      }
    }
  }
}

/**
 * Ensure we're being consistent with object keys in S3
 */
data class SetupPhotoKey(
  val invite: UUID,
) : S3Keyable()

/**
 * Ensure we're being consistent with object keys in S3
 */
data class CheckinVideoKey(
  val checkin: UUID,
) : S3Keyable()

data class CheckinPhotoKey(
  val checkin: UUID,
  val index: Int,
) : S3Keyable()

@Service
class S3UploadService(
  @Qualifier("MOJ") private val s3uploadClient: S3Client,
  private val s3Presigner: S3Presigner,
  @Value("\${aws.s3.image-uploads}") private val imageUploadBucket: String,
  @Value("\${aws.s3.video-uploads}") private val videoUploadBucket: String,
) : ResourceLocator {

  private fun putObjectRequest(bucket: String, key: String, contentType: String): PutObjectRequest {
    val request = PutObjectRequest.builder()
      .bucket(bucket)
      .key(key)
      .contentType(contentType)
      .build()
    return request
  }

  private fun putObjectRequest(setup: OffenderSetup, contentType: String): PutObjectRequest = putObjectRequest(imageUploadBucket, SetupPhotoKey(setup.offender.uuid).toKey(), contentType)

  private fun putObjectRequest(checkin: OffenderCheckin, contentType: String): PutObjectRequest = putObjectRequest(videoUploadBucket, CheckinVideoKey(checkin.uuid).toKey(), contentType)

  /**
   * We can have multiple checkin photo objects, hence the index param
   */
  private fun putObjectRequest(checkin: OffenderCheckin, contentType: String, index: Int): PutObjectRequest = putObjectRequest(videoUploadBucket, CheckinPhotoKey(checkin.uuid, index).toKey(), contentType)

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

  fun generatePresignedUploadUrl(
    checkin: OffenderCheckin,
    contentType: String = "application/octet-stream",
    index: Int,
    duration: Duration,
  ): URL {
    val putRequest = putObjectRequest(checkin, contentType, index)
    val presignRequest = PutObjectPresignRequest.builder()
      .putObjectRequest(putRequest)
      .signatureDuration(duration)
      .build()
    return s3Presigner.presignPutObject(presignRequest).url()
  }

  fun generatePresignedUploadUrl(
    offender: Offender,
    contentType: String = "application/octet-stream",
    duration: Duration,
  ): URL = // we use the `SetupPhotoKey` as that places the image under the 'reference photo' key
    generatePresignedUploadUrl(SetupPhotoKey(offender.uuid), contentType, duration)

  private fun generatePresignedUploadUrl(
    keyable: S3Keyable,
    contentType: String = "application/octet-stream",
    duration: Duration,
  ): URL {
    val putRequest = putObjectRequest(bucketFor(keyable), keyable.toKey(), contentType)
    val presignRequest = PutObjectPresignRequest.builder()
      .putObjectRequest(putRequest)
      .signatureDuration(duration)
      .build()
    return s3Presigner.presignPutObject(presignRequest).url()
  }

  private fun bucketFor(key: S3Keyable): String {
    when (key) {
      is SetupPhotoKey -> {
        return this.imageUploadBucket
      }
      is CheckinVideoKey -> {
        return this.videoUploadBucket
      }
      is CheckinPhotoKey -> {
        return this.imageUploadBucket
      }
    }
  }

  private fun getHeadObjectRequest(key: S3Keyable): HeadObjectRequest = HeadObjectRequest.builder()
    .bucket(this.bucketFor(key))
    .key(key.toKey())
    .build()

  private fun keyExists(key: S3Keyable): Boolean {
    val request = getHeadObjectRequest(key)
    return isObjectUploaded(request)
  }

  fun isSetupPhotoUploaded(setup: OffenderSetup): Boolean {
    val photoKey = SetupPhotoKey(setup.offender.uuid)
    return keyExists(photoKey)
  }

  fun isCheckinVideoUploaded(checkin: OffenderCheckin): Boolean {
    val videoKey = CheckinVideoKey(checkin.uuid)
    return keyExists(videoKey)
  }

  private fun isObjectUploaded(request: HeadObjectRequest): Boolean {
    try {
      s3uploadClient.headObject(request)
      return true
    } catch (e: NoSuchKeyException) {
      return false
    }
  }

  private fun getObjectRequestFor(key: S3Keyable): GetObjectRequest = GetObjectRequest.builder()
    .bucket(this.bucketFor(key))
    .key(key.toKey())
    .build()

  private fun presignedGetUrlFor(key: S3Keyable): URL {
    val getRequest = getObjectRequestFor(key)
    val presignRequest = GetObjectPresignRequest.builder()
      .signatureDuration(Duration.ofMinutes(5))
      .getObjectRequest(getRequest)
      .build()
    val presigned = this.s3Presigner.presignGetObject(presignRequest)
    return presigned.url()
  }

  override fun getOffenderPhoto(offender: Offender): URL? {
    if (offender.status == OffenderStatus.VERIFIED) {
      val photoKey = SetupPhotoKey(offender.uuid)
      return presignedGetUrlFor(photoKey)
    } else {
      return null
    }
  }

  override fun getCheckinVideo(checkin: OffenderCheckin): URL? {
    if (checkin.submittedAt != null) {
      val videoKey = CheckinVideoKey(checkin.uuid)
      return presignedGetUrlFor(videoKey)
    } else {
      return null
    }
  }

  override fun getCheckinSnapshot(checkin: OffenderCheckin): URL? {
    if (checkin.submittedAt != null) {
      val photoKey = CheckinPhotoKey(checkin.uuid, 1)
      return presignedGetUrlFor(photoKey)
    } else {
      return null
    }
  }
}
