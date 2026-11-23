package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition.S3ObjectCoordinate
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Resolve a client-supplied base64 SHA-256 to forward to S3.
 *
 * Rejects with HTTP 400 when the hash is missing — a content hash is mandatory for all uploads.
 *
 * The value is passed verbatim to the AWS SDK's `.checksumSHA256(...)`. The SDK / S3 will
 * reject malformed values at PUT time, so we don't validate format here beyond non-empty.
 * The slot label is included in the error message so multi-file callers can identify which input failed.
 */
fun resolveUploadHash(
  sha256Base64: String?,
  slot: String = "file",
): String {
  val trimmed = sha256Base64?.trim()
  if (trimmed.isNullOrEmpty()) {
    throw org.springframework.web.server.ResponseStatusException(
      org.springframework.http.HttpStatus.BAD_REQUEST,
      "Missing content hash for $slot",
    )
  }
  return trimmed
}

sealed class S3Keyable {
  fun toKey(): String {
    when (this) {
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
data class CheckinVideoKey(
  val checkin: UUID,
) : S3Keyable()

data class CheckinPhotoKey(
  val checkin: UUID,
  val index: Int,
) : S3Keyable()

/** Storage for V2 check-in media. */
@Service("v2S3UploadService")
class S3UploadService(
  @Qualifier("MOJ") private val s3uploadClient: S3Client,
  private val s3Presigner: S3Presigner,
  @Value("\${aws.s3.video-uploads}") private val videoUploadBucket: String,
  private val versionPurger: S3VersionPurger,
) {

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

  private fun getHeadObjectRequest(key: S3Keyable): HeadObjectRequest = HeadObjectRequest.builder()
    .bucket(videoUploadBucket)
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

  private fun presignedGetUrlFor(key: S3Keyable): URL = s3Presigner.presignGet(videoUploadBucket, key.toKey(), Duration.ofMinutes(5))

  private fun generatePresignedUploadUrl(
    keyable: S3Keyable,
    contentType: String = "application/octet-stream",
    duration: Duration,
  ): URL = generatePresignedUpload(keyable, contentType, duration, checksumSha256Base64 = null).url

  private fun generatePresignedUpload(
    keyable: S3Keyable,
    contentType: String,
    duration: Duration,
    checksumSha256Base64: String?,
  ): PresignedUpload = s3Presigner.presignPut(videoUploadBucket, keyable.toKey(), contentType, duration, checksumSha256Base64)

  private fun deleteObjectRequest(bucket: String, key: String): DeleteObjectRequest {
    val request = DeleteObjectRequest.builder()
      .bucket(bucket)
      .key(key)
      .build()
    return request
  }

  // ============================================================
  // V2 Public API - Checkin Operations
  // ============================================================

  /**
   * V2 Checkin - generates presigned upload URL for video
   */
  fun generatePresignedUploadUrl(
    checkin: OffenderCheckin,
    contentType: String = "application/octet-stream",
    duration: Duration,
  ): URL = generatePresignedUploadUrl(CheckinVideoKey(checkin.uuid), contentType, duration)

  /**
   * V2 Checkin - generates presigned video upload, optionally signed with a content hash.
   */
  fun generatePresignedUpload(
    checkin: OffenderCheckin,
    contentType: String = "application/octet-stream",
    duration: Duration,
    checksumSha256Base64: String? = null,
  ): PresignedUpload = generatePresignedUpload(CheckinVideoKey(checkin.uuid), contentType, duration, checksumSha256Base64)

  /**
   * V2 Checkin - generates presigned upload URL for snapshot at index
   */
  fun generatePresignedUploadUrl(
    checkin: OffenderCheckin,
    contentType: String = "application/octet-stream",
    index: Int,
    duration: Duration,
  ): URL = generatePresignedUploadUrl(CheckinPhotoKey(checkin.uuid, index), contentType, duration)

  /**
   * V2 Checkin - generates presigned snapshot upload, optionally signed with a content hash.
   */
  fun generatePresignedUpload(
    checkin: OffenderCheckin,
    contentType: String = "application/octet-stream",
    index: Int,
    duration: Duration,
    checksumSha256Base64: String? = null,
  ): PresignedUpload = generatePresignedUpload(CheckinPhotoKey(checkin.uuid, index), contentType, duration, checksumSha256Base64)

  /**
   * V2 Checkin - checks if video is uploaded
   */
  fun isCheckinVideoUploaded(checkin: OffenderCheckin): Boolean {
    val videoKey = CheckinVideoKey(checkin.uuid)
    return keyExists(videoKey)
  }

  /**
   * V2 Checkin - checks if snapshot at index exists
   */
  fun isCheckinSnapshotUploaded(checkin: OffenderCheckin, index: Int): Boolean {
    val snapshotKey = CheckinPhotoKey(checkin.uuid, index)
    return keyExists(snapshotKey)
  }

  /**
   * V2 Checkin - gets presigned download URL for video if it exists in S3
   * Returns null if video hasn't been uploaded yet
   */
  fun getCheckinVideo(checkin: OffenderCheckin): URL? {
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
  fun getCheckinSnapshot(checkin: OffenderCheckin, index: Int): URL? {
    val photoKey = CheckinPhotoKey(checkin.uuid, index)
    return if (keyExists(photoKey)) {
      presignedGetUrlFor(photoKey)
    } else {
      null
    }
  }

  /**
   * V2 Checkin - uploads raw bytes as a checkin snapshot
   */
  @CircuitBreaker(name = "awsS3")
  @Retry(name = "awsS3")
  fun uploadCheckinSnapshot(checkin: OffenderCheckin, index: Int, bytes: ByteArray, contentType: String): S3ObjectCoordinate {
    val key = CheckinPhotoKey(checkin.uuid, index)
    val putReq = PutObjectRequest.builder().bucket(videoUploadBucket).key(key.toKey()).contentType(contentType).build()
    s3uploadClient.putObject(putReq, RequestBody.fromBytes(bytes))
    return S3ObjectCoordinate(bucket = videoUploadBucket, key = key.toKey())
  }

  /**
   * V2 Checkin - gets S3 object coordinate for snapshot (used by Rekognition)
   */
  fun checkinObjectCoordinate(checkin: OffenderCheckin, index: Int): S3ObjectCoordinate {
    val key = CheckinPhotoKey(checkin.uuid, index)
    return S3ObjectCoordinate(
      bucket = videoUploadBucket,
      key = key.toKey(),
    )
  }

  /**
   * V2 Checkin - deletes S3 object for checkin snapshot
   */
  @CircuitBreaker(name = "awsS3")
  @Retry(name = "awsS3")
  fun deleteCheckinSnapshot(uuid: UUID, index: Int): DeleteObjectResponse? {
    val key = CheckinPhotoKey(uuid, index)
    val delReq = deleteObjectRequest(videoUploadBucket, key.toKey())
    return s3uploadClient.deleteObject(delReq)
  }

  /**
   * V2 Checkin - deletes S3 object for checkin video
   */
  @CircuitBreaker(name = "awsS3")
  @Retry(name = "awsS3")
  fun deleteCheckinVideo(uuid: UUID): DeleteObjectResponse? {
    val key = CheckinVideoKey(uuid)
    val delReq = deleteObjectRequest(videoUploadBucket, key.toKey())
    return s3uploadClient.deleteObject(delReq)
  }

  /** Permanently deletes the video and all snapshots for a check-in, including delete markers. */
  fun purgeCheckinObjects(checkinUuid: UUID): S3PurgeResult = versionPurger.purgeAllVersions(videoUploadBucket, prefix = "checkin-$checkinUuid/") { true }

  companion object {
    val LOG = org.slf4j.LoggerFactory.getLogger(S3UploadService::class.java)!!
  }
}
