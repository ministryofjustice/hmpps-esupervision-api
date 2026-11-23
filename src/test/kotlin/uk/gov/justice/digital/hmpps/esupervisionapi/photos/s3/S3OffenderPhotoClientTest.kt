package uk.gov.justice.digital.hmpps.esupervisionapi.photos.s3

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectVersion
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.OffenderPhotoPurgeResult
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.OffenderPhotoUpload
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.ReferencePhotoLocation
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3VersionPurger
import java.net.URI
import java.time.Duration
import java.util.UUID

class S3OffenderPhotoClientTest {
  private val s3: S3Client = mock()
  private val presigner: S3Presigner = mock()
  private val bucket = "image-bucket"
  private val offenderUuid = UUID.randomUUID()
  private val key = "setup-$offenderUuid"
  private val url = URI.create("https://image-bucket.example/$key").toURL()
  private val client = S3OffenderPhotoClient(s3, presigner, bucket, S3VersionPurger(s3))

  @Test
  fun `upload signs the checksum and returns the header the uploader must send`() {
    val presigned = mock<PresignedPutObjectRequest>()
    whenever(presigned.url()).thenReturn(url)
    whenever(presigner.presignPutObject(any<PutObjectPresignRequest>())).thenReturn(presigned)
    val duration = Duration.ofMinutes(5)

    val result = client.presignUpload(offenderUuid, "image/png", duration, "hash")

    val request = argumentCaptor<PutObjectPresignRequest>()
    verify(presigner).presignPutObject(request.capture())
    assertThat(request.firstValue.signatureDuration()).isEqualTo(duration)
    val put = request.firstValue.putObjectRequest()
    assertThat(put.bucket()).isEqualTo(bucket)
    assertThat(put.key()).isEqualTo(key)
    assertThat(put.contentType()).isEqualTo("image/png")
    assertThat(put.checksumSHA256()).isEqualTo("hash")
    assertThat(result).isEqualTo(OffenderPhotoUpload(url, mapOf("x-amz-checksum-sha256" to "hash")))
  }

  @Test
  fun `read URL and reference location point to the same photo without checking existence`() {
    val presigned = mock<PresignedGetObjectRequest>()
    whenever(presigned.url()).thenReturn(url)
    whenever(presigner.presignGetObject(any<GetObjectPresignRequest>())).thenReturn(presigned)

    assertThat(client.presignRead(offenderUuid)).isEqualTo(url)
    assertThat(client.referenceLocation(offenderUuid)).isEqualTo(ReferencePhotoLocation(bucket, key))

    val request = argumentCaptor<GetObjectPresignRequest>()
    verify(presigner).presignGetObject(request.capture())
    assertThat(request.firstValue.signatureDuration()).isEqualTo(Duration.ofMinutes(5))
    assertThat(request.firstValue.getObjectRequest().bucket()).isEqualTo(bucket)
    assertThat(request.firstValue.getObjectRequest().key()).isEqualTo(key)
    verifyNoInteractions(s3)
  }

  @Test
  fun `presence uses the exact photo and only a missing key becomes false`() {
    val request = HeadObjectRequest.builder().bucket(bucket).key(key).build()
    whenever(s3.headObject(request))
      .thenReturn(HeadObjectResponse.builder().build())
      .thenThrow(NoSuchKeyException.builder().build())
      .thenThrow(S3Exception.builder().statusCode(403).build())

    assertThat(client.isUploaded(offenderUuid)).isTrue()
    assertThat(client.isUploaded(offenderUuid)).isFalse()
    assertThat(assertThrows<S3Exception> { client.isUploaded(offenderUuid) }.statusCode()).isEqualTo(403)
  }

  @Test
  fun `purge deletes all versions and markers for the exact key only`() {
    val listing = ListObjectVersionsRequest.builder().bucket(bucket).prefix(key).build()
    whenever(s3.listObjectVersions(listing)).thenReturn(
      ListObjectVersionsResponse.builder()
        .versions(
          ObjectVersion.builder().key(key).versionId("v1").build(),
          ObjectVersion.builder().key("${key}x").versionId("other").build(),
        )
        .deleteMarkers(DeleteMarkerEntry.builder().key(key).versionId("dm1").build())
        .build(),
    )
    whenever(s3.deleteObjects(any<DeleteObjectsRequest>())).thenReturn(DeleteObjectsResponse.builder().build())

    assertThat(client.purge(offenderUuid)).isEqualTo(OffenderPhotoPurgeResult(keysDeleted = 1, keysFailed = 0))
    val request = argumentCaptor<DeleteObjectsRequest>()
    verify(s3).deleteObjects(request.capture())
    assertThat(request.firstValue.bucket()).isEqualTo(bucket)
    assertThat(request.firstValue.delete().objects().map { it.key() to it.versionId() })
      .containsExactlyInAnyOrder(key to "v1", key to "dm1")
  }

  @Test
  fun `purging an absent photo deletes and counts nothing`() {
    whenever(s3.listObjectVersions(any<ListObjectVersionsRequest>())).thenReturn(ListObjectVersionsResponse.builder().build())

    assertThat(client.purge(offenderUuid)).isEqualTo(OffenderPhotoPurgeResult(keysDeleted = 0, keysFailed = 0))
    verify(s3, never()).deleteObjects(any<DeleteObjectsRequest>())
  }
}
