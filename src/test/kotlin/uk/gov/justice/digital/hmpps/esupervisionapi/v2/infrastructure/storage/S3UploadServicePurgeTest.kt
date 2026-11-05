package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse
import software.amazon.awssdk.services.s3.model.ObjectVersion
import software.amazon.awssdk.services.s3.model.S3Error
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.util.UUID

/**
 * The test support cleanup's S3 deletes. The bucket is versioned, so a plain delete only adds a
 * delete marker: every version and marker has to go for the photo or video to really be removed.
 */
class S3UploadServicePurgeTest {

  private val imageBucket = "image-bucket"
  private val videoBucket = "video-bucket"
  private val s3: S3Client = mock()
  private val service = S3UploadService(s3, mock<S3Presigner>(), imageBucket, videoBucket)

  private val checkin = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
  private val offender = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

  private fun version(key: String, id: String) = ObjectVersion.builder().key(key).versionId(id).build()
  private fun marker(key: String, id: String) = DeleteMarkerEntry.builder().key(key).versionId(id).build()

  private fun page(
    versions: List<ObjectVersion> = emptyList(),
    markers: List<DeleteMarkerEntry> = emptyList(),
    next: Pair<String, String>? = null,
  ) = ListObjectVersionsResponse.builder()
    .versions(versions)
    .deleteMarkers(markers)
    .isTruncated(next != null)
    .nextKeyMarker(next?.first)
    .nextVersionIdMarker(next?.second)
    .build()

  private fun deletesSucceed() {
    whenever(s3.deleteObjects(any<DeleteObjectsRequest>())).thenReturn(DeleteObjectsResponse.builder().build())
  }

  private fun deleted(): List<DeleteObjectsRequest> {
    val captor = argumentCaptor<DeleteObjectsRequest>()
    verify(s3, org.mockito.kotlin.atLeastOnce()).deleteObjects(captor.capture())
    return captor.allValues
  }

  @Test
  fun `a check-in's every version and delete marker is removed, across pages, from the video bucket`() {
    val listings = argumentCaptor<ListObjectVersionsRequest>()
    whenever(s3.listObjectVersions(listings.capture())).thenReturn(
      page(
        versions = listOf(version("checkin-$checkin/video", "v1"), version("checkin-$checkin/video", "v2")),
        next = "checkin-$checkin/video" to "v2",
      ),
      page(
        versions = listOf(version("checkin-$checkin/0", "v3")),
        markers = listOf(marker("checkin-$checkin/1", "dm1")),
      ),
    )
    deletesSucceed()

    val result = service.purgeCheckinObjects(checkin)

    assertThat(listings.allValues).allSatisfy {
      assertThat(it.bucket()).isEqualTo(videoBucket)
      assertThat(it.prefix()).isEqualTo("checkin-$checkin/")
    }
    assertThat(listings.secondValue.keyMarker()).isEqualTo("checkin-$checkin/video")
    assertThat(listings.secondValue.versionIdMarker()).isEqualTo("v2")

    val request = deleted().single()
    assertThat(request.bucket()).isEqualTo(videoBucket)
    assertThat(request.delete().objects().map { it.key() to it.versionId() }).containsExactlyInAnyOrder(
      "checkin-$checkin/video" to "v1",
      "checkin-$checkin/video" to "v2",
      "checkin-$checkin/0" to "v3",
      "checkin-$checkin/1" to "dm1",
    )
    assertThat(result).isEqualTo(S3PurgeResult(keysDeleted = 3, keysFailed = 0))
  }

  @Test
  fun `the setup photo is removed from the image bucket, and only its exact key`() {
    whenever(s3.listObjectVersions(any<ListObjectVersionsRequest>())).thenReturn(
      page(
        versions = listOf(version("setup-$offender", "v1"), version("setup-${offender}x", "other")),
        markers = listOf(marker("setup-$offender", "dm1")),
      ),
    )
    deletesSucceed()

    val result = service.purgeSetupPhoto(offender)

    val request = deleted().single()
    assertThat(request.bucket()).isEqualTo(imageBucket)
    assertThat(request.delete().objects().map { it.key() to it.versionId() })
      .containsExactlyInAnyOrder("setup-$offender" to "v1", "setup-$offender" to "dm1")
    assertThat(result).isEqualTo(S3PurgeResult(keysDeleted = 1, keysFailed = 0))
  }

  @Test
  fun `nothing stored means nothing deleted and nothing counted`() {
    whenever(s3.listObjectVersions(any<ListObjectVersionsRequest>())).thenReturn(page())

    assertThat(service.purgeSetupPhoto(offender)).isEqualTo(S3PurgeResult(keysDeleted = 0, keysFailed = 0))
    verify(s3, never()).deleteObjects(any<DeleteObjectsRequest>())
  }

  @Test
  fun `keys S3 refuses to delete are counted as failed, not deleted`() {
    whenever(s3.listObjectVersions(any<ListObjectVersionsRequest>())).thenReturn(
      page(versions = listOf(version("checkin-$checkin/video", "v1"), version("checkin-$checkin/0", "v2"))),
    )
    whenever(s3.deleteObjects(any<DeleteObjectsRequest>())).thenReturn(
      DeleteObjectsResponse.builder()
        .errors(S3Error.builder().key("checkin-$checkin/video").versionId("v1").code("AccessDenied").build())
        .build(),
    )

    assertThat(service.purgeCheckinObjects(checkin)).isEqualTo(S3PurgeResult(keysDeleted = 1, keysFailed = 1))
  }

  @Test
  fun `a batch that fails outright counts its keys as failed, and later batches still go`() {
    whenever(s3.listObjectVersions(any<ListObjectVersionsRequest>())).thenReturn(
      page(versions = (1..2500).map { version("checkin-$checkin/$it", "v$it") }),
    )
    whenever(s3.deleteObjects(any<DeleteObjectsRequest>()))
      .thenReturn(DeleteObjectsResponse.builder().build())
      .thenThrow(SdkClientException.create("connection reset"))
      .thenReturn(DeleteObjectsResponse.builder().build())

    val result = service.purgeCheckinObjects(checkin)

    verify(s3, times(3)).deleteObjects(any<DeleteObjectsRequest>())
    assertThat(result).isEqualTo(S3PurgeResult(keysDeleted = 1500, keysFailed = 1000))
  }

  @Test
  fun `more than 1000 versions are deleted in batches of 1000`() {
    whenever(s3.listObjectVersions(any<ListObjectVersionsRequest>())).thenReturn(
      page(versions = (1..2500).map { version("checkin-$checkin/$it", "v$it") }),
    )
    deletesSucceed()

    service.purgeCheckinObjects(checkin)

    verify(s3, times(3)).deleteObjects(any<DeleteObjectsRequest>())
    assertThat(deleted().map { it.delete().objects().size }).containsExactly(1000, 1000, 500)
  }
}
