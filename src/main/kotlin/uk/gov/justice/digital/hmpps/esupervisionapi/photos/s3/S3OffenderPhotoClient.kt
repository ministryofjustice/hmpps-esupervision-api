package uk.gov.justice.digital.hmpps.esupervisionapi.photos.s3

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.OffenderPhotoClient
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.OffenderPhotoPurgeResult
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.OffenderPhotoUpload
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.ReferencePhotoLocation
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3VersionPurger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.presignGet
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.presignPut
import java.net.URL
import java.time.Duration
import java.util.UUID

@Service
internal class S3OffenderPhotoClient(
  @Qualifier("MOJ") private val s3Client: S3Client,
  private val presigner: S3Presigner,
  @Value("\${aws.s3.image-uploads}") private val bucket: String,
  private val versionPurger: S3VersionPurger,
) : OffenderPhotoClient {
  override fun presignUpload(offenderUuid: UUID, contentType: String, duration: Duration, checksumSha256Base64: String): OffenderPhotoUpload {
    val upload = presigner.presignPut(bucket, photoKey(offenderUuid), contentType, duration, checksumSha256Base64)
    return OffenderPhotoUpload(upload.url, upload.requiredHeaders)
  }

  override fun presignRead(offenderUuid: UUID): URL = presigner.presignGet(bucket, photoKey(offenderUuid), Duration.ofMinutes(5))

  // The legacy HEAD check bypassed Spring advice via self-invocation. Preserve that behavior here.
  override fun isUploaded(offenderUuid: UUID): Boolean = try {
    s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(photoKey(offenderUuid)).build())
    true
  } catch (e: NoSuchKeyException) {
    false
  }

  override fun referenceLocation(offenderUuid: UUID): ReferencePhotoLocation = ReferencePhotoLocation(bucket, photoKey(offenderUuid))

  override fun purge(offenderUuid: UUID): OffenderPhotoPurgeResult {
    val key = photoKey(offenderUuid)
    val result = versionPurger.purgeAllVersions(bucket, prefix = key) { it == key }
    return OffenderPhotoPurgeResult(result.keysDeleted, result.keysFailed)
  }

  private fun photoKey(offenderUuid: UUID): String = "setup-$offenderUuid"
}
