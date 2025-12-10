package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.S3ObjectCoordinate
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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

/**
 * V2 S3 Upload Service
 * Handles S3 operations for V2 entities only
 * Complete independence from V1
 */
@Service("v2S3UploadService")
class S3UploadService(
  @Qualifier("MOJ") private val s3uploadClient: S3Client,
  private val s3Presigner: S3Presigner,
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

  @CircuitBreaker(name = "awsS3")
  @Retry(name = "awsS3")
  fun copyFromPresignedGet(
    sourceUrl: URL,
    destination: S3ObjectCoordinate,
    contentType: String? = null,
  ): S3ObjectCoordinate {
    val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val getReq = HttpRequest.newBuilder(sourceUrl.toURI()).GET().build()
    val getRes = http.send(getReq, HttpResponse.BodyHandlers.ofInputStream())
    if (getRes.statusCode() != 200) {
      throw IllegalStateException("Failed to download from presigned source: HTTP ${getRes.statusCode()}")
    }

    val lenHeader = getRes.headers().firstValue("content-length")
    val contentLen = if (lenHeader.isPresent) lenHeader.get().toLong() else -1L
    val resolvedContentType = contentType
      ?: getRes.headers().firstValue("content-type").orElse("application/octet-stream")

    LOG.info("content-length={}, content-type={}, resolved content-type={}", lenHeader.orElse("unknown"), contentType, resolvedContentType)
    LOG.debug("sourceUrl={}", sourceUrl)

    val putReq = PutObjectRequest.builder()
      .bucket(destination.bucket)
      .key(destination.key)
      .contentType(resolvedContentType)
      .build()

    getRes.body().use { inStream ->
      if (contentLen >= 0) {
        s3uploadClient.putObject(putReq, RequestBody.fromInputStream(inStream, contentLen))
      } else {
        // Fallback when Content-Length is absent (should be rare for S3 GET)
        val bytes = inStream.readAllBytes()
        s3uploadClient.putObject(putReq, RequestBody.fromBytes(bytes))
      }
    }

    return destination
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
        return this.videoUploadBucket
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

  @CircuitBreaker(name = "awsS3")
  @Retry(name = "awsS3")
  internal fun isObjectUploaded(request: HeadObjectRequest): Boolean {
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

  // ============================================================
  // V2 Public API - Setup Photo Operations
  // ============================================================

  /**
   * V2 Setup - generates presigned upload URL for setup photo
   */
  fun generatePresignedUploadUrl(
    setup: OffenderSetupV2,
    contentType: String = "application/octet-stream",
    duration: Duration,
  ): URL = generatePresignedUploadUrl(SetupPhotoKey(setup.offender.uuid), contentType, duration)

  /**
   * V2 Offender - generates presigned upload URL for updating offender photo
   */
  fun generatePresignedUploadUrl(
    offender: OffenderV2,
    contentType: String = "application/octet-stream",
    duration: Duration,
  ): URL = generatePresignedUploadUrl(SetupPhotoKey(offender.uuid), contentType, duration)

  /**
   * V2 Setup - checks if setup photo is uploaded
   */
  fun isSetupPhotoUploaded(setup: OffenderSetupV2): Boolean {
    val photoKey = SetupPhotoKey(setup.offender.uuid)
    return keyExists(photoKey)
  }

  /**
   * V2 Offender - checks if setup photo is uploaded (overload for direct offender check)
   */
  fun isSetupPhotoUploaded(offender: OffenderV2): Boolean {
    val photoKey = SetupPhotoKey(offender.uuid)
    return keyExists(photoKey)
  }

  /**
   * V2 Offender - gets presigned download URL for offender photo
   */
  fun getOffenderPhoto(offender: OffenderV2): URL? {
    if (offender.status == OffenderStatus.VERIFIED) {
      val photoKey = SetupPhotoKey(offender.uuid)
      return presignedGetUrlFor(photoKey)
    } else {
      return null
    }
  }

  /**
   * V2 Offender - gets S3 object coordinate for setup photo (used by Rekognition)
   */
  fun setupPhotoObjectCoordinate(offender: OffenderV2): S3ObjectCoordinate {
    val key = SetupPhotoKey(offender.uuid)
    return S3ObjectCoordinate(
      bucket = imageUploadBucket,
      key = key.toKey(),
    )
  }

  // ============================================================
  // V2 Public API - Checkin Operations
  // ============================================================

  /**
   * V2 Checkin - generates presigned upload URL for video
   */
  fun generatePresignedUploadUrl(
    checkin: OffenderCheckinV2,
    contentType: String = "application/octet-stream",
    duration: Duration,
  ): URL = generatePresignedUploadUrl(CheckinVideoKey(checkin.uuid), contentType, duration)

  /**
   * V2 Checkin - generates presigned upload URL for snapshot at index
   */
  fun generatePresignedUploadUrl(
    checkin: OffenderCheckinV2,
    contentType: String = "application/octet-stream",
    index: Int,
    duration: Duration,
  ): URL = generatePresignedUploadUrl(CheckinPhotoKey(checkin.uuid, index), contentType, duration)

  /**
   * V2 Checkin - checks if video is uploaded
   */
  fun isCheckinVideoUploaded(checkin: OffenderCheckinV2): Boolean {
    val videoKey = CheckinVideoKey(checkin.uuid)
    return keyExists(videoKey)
  }

  /**
   * V2 Checkin - checks if snapshot at index exists
   */
  fun isCheckinSnapshotUploaded(checkin: OffenderCheckinV2, index: Int): Boolean {
    val snapshotKey = CheckinPhotoKey(checkin.uuid, index)
    return keyExists(snapshotKey)
  }

  /**
   * V2 Checkin - gets presigned download URL for video if it exists in S3
   * Returns null if video hasn't been uploaded yet
   */
  fun getCheckinVideo(checkin: OffenderCheckinV2): URL? {
    val videoKey = CheckinVideoKey(checkin.uuid)
    return if (keyExists(videoKey)) {
      presignedGetUrlFor(videoKey)
    } else {
      null
    }
  }

  /**
   * V2 Checkin - gets presigned download URL for snapshot at index if it exists
   * Returns null if snapshot hasn't been uploaded yet
   */
  fun getCheckinSnapshot(checkin: OffenderCheckinV2, index: Int): URL? {
    val photoKey = CheckinPhotoKey(checkin.uuid, index)
    return if (keyExists(photoKey)) {
      presignedGetUrlFor(photoKey)
    } else {
      null
    }
  }

  /**
   * V2 Checkin - gets S3 object coordinate for snapshot (used by Rekognition)
   */
  fun checkinObjectCoordinate(checkin: OffenderCheckinV2, index: Int): S3ObjectCoordinate {
    val key = CheckinPhotoKey(checkin.uuid, index)
    return S3ObjectCoordinate(
      bucket = videoUploadBucket,
      key = key.toKey(),
    )
  }

  companion object {
    val LOG = org.slf4j.LoggerFactory.getLogger(S3UploadService::class.java)!!
  }
}
