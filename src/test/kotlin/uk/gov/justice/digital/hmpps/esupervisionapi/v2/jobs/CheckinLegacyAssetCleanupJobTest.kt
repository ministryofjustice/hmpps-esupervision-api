package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse
import software.amazon.awssdk.services.s3.model.ObjectVersion
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2Repository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class CheckinLegacyAssetCleanupJobTest {

  private val bucket = "checkins-bucket"
  private val clock = Clock.fixed(Instant.parse("2026-05-13T12:00:00Z"), ZoneId.of("UTC"))
  private val jobLogRepository: JobLogV2Repository = mock {
    on { saveAndFlush(any<JobLogV2>()) } doAnswer { it.arguments[0] as JobLogV2 }
  }

  private fun job(s3: S3Client, dryRun: Boolean = false) =
    CheckinLegacyAssetCleanupJob(clock, s3, jobLogRepository, bucket, dryRun)

  private fun version(key: String, versionId: String = "v1") =
    ObjectVersion.builder().key(key).versionId(versionId).build()

  private fun deleteMarker(key: String, versionId: String = "dm1") =
    DeleteMarkerEntry.builder().key(key).versionId(versionId).build()

  private fun listPage(
    versions: List<ObjectVersion> = emptyList(),
    deleteMarkers: List<DeleteMarkerEntry> = emptyList(),
    truncated: Boolean = false,
    nextKeyMarker: String? = null,
    nextVersionIdMarker: String? = null,
  ): ListObjectVersionsResponse = ListObjectVersionsResponse.builder()
    .versions(versions)
    .deleteMarkers(deleteMarkers)
    .isTruncated(truncated)
    .nextKeyMarker(nextKeyMarker)
    .nextVersionIdMarker(nextVersionIdMarker)
    .build()

  private fun captureDeletedKeys(s3: S3Client): List<Pair<String, String?>> {
    val captor = argumentCaptor<DeleteObjectsRequest>()
    verify(s3).deleteObjects(captor.capture())
    return captor.firstValue.delete().objects().map { it.key() to it.versionId() }
  }

  // ---- per-filename behaviour (cleanupCheckinFile) ----------------------------------------

  @Test
  fun `cleanupCheckinFile('1') deletes only checkin-uuid-slash-1 keys and ignores siblings`() {
    val uuid = "550e8400-e29b-41d4-a716-446655440000"
    val target = "checkin-$uuid/1"
    val versions = listOf(
      version(target),
      version("checkin-$uuid/0"), // keep: reference image
      version("checkin-$uuid/video"), // keep: not the filename we're cleaning right now
      version("checkin-$uuid/10"), // keep: looks like /1 but isn't
      version("checkin-$uuid/11"),
      version("checkin-$uuid/1/sub"), // keep: extra path segment after /1
      version("checkin-$uuid/2"),
      version("setup-$uuid"), // keep: setup photo
      version("setup-$uuid/1"), // keep: not a checkin- prefix
      version("checkin-not-a-uuid/1"), // keep: uuid section invalid
      version("checkin-${uuid.uppercase()}/1"), // delete: hex regex is case-insensitive
    )
    val s3: S3Client = mock {
      on { listObjectVersions(any<ListObjectVersionsRequest>()) } doReturn listPage(versions = versions)
      on { deleteObjects(any<DeleteObjectsRequest>()) } doReturn DeleteObjectsResponse.builder().build()
    }

    job(s3).cleanupCheckinFile("1")

    assertThat(captureDeletedKeys(s3).map { it.first })
      .containsExactlyInAnyOrder(target, "checkin-${uuid.uppercase()}/1")
  }

  @Test
  fun `cleanupCheckinFile('video') deletes only checkin-uuid-slash-video keys and ignores near-misses`() {
    val uuid = "550e8400-e29b-41d4-a716-446655440000"
    val target = "checkin-$uuid/video"
    val versions = listOf(
      version(target),
      version("checkin-$uuid/videos"), // keep: trailing s
      version("checkin-$uuid/video/extra"), // keep: extra path segment
      version("checkin-$uuid/VIDEO"), // keep: filename is case-sensitive
      version("checkin-$uuid/0"),
      version("checkin-$uuid/1"),
      version("setup-$uuid/video"),
      version("checkin-not-a-uuid/video"),
    )
    val s3: S3Client = mock {
      on { listObjectVersions(any<ListObjectVersionsRequest>()) } doReturn listPage(versions = versions)
      on { deleteObjects(any<DeleteObjectsRequest>()) } doReturn DeleteObjectsResponse.builder().build()
    }

    job(s3).cleanupCheckinFile("video")

    assertThat(captureDeletedKeys(s3).map { it.first }).containsExactly(target)
  }

  @Test
  fun `every version and delete marker for a matching key is queued for permanent removal`() {
    val uuid = "11111111-2222-3333-4444-555555555555"
    val key = "checkin-$uuid/1"
    val s3: S3Client = mock {
      on { listObjectVersions(any<ListObjectVersionsRequest>()) } doReturn listPage(
        versions = listOf(version(key, "ver-a"), version(key, "ver-b"), version(key, "ver-c")),
        deleteMarkers = listOf(deleteMarker(key, "dm-x")),
      )
      on { deleteObjects(any<DeleteObjectsRequest>()) } doReturn DeleteObjectsResponse.builder().build()
    }

    job(s3).cleanupCheckinFile("1")

    assertThat(captureDeletedKeys(s3)).containsExactlyInAnyOrder(
      key to "ver-a",
      key to "ver-b",
      key to "ver-c",
      key to "dm-x",
    )
  }

  @Test
  fun `dry-run never calls deleteObjects`() {
    val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    val s3: S3Client = mock {
      on { listObjectVersions(any<ListObjectVersionsRequest>()) } doReturn listPage(
        versions = listOf(version("checkin-$uuid/1"), version("checkin-$uuid/0")),
      )
    }

    job(s3, dryRun = true).cleanupCheckinFile("1")

    verify(s3, never()).deleteObjects(any<DeleteObjectsRequest>())
  }

  @Test
  fun `pagination follows nextKeyMarker and nextVersionIdMarker until exhausted`() {
    val u1 = "11111111-1111-1111-1111-111111111111"
    val u2 = "22222222-2222-2222-2222-222222222222"
    val s3: S3Client = mock {
      on { listObjectVersions(any<ListObjectVersionsRequest>()) }
        .thenReturn(
          listPage(
            versions = listOf(version("checkin-$u1/1", "v-1")),
            truncated = true,
            nextKeyMarker = "checkin-$u1/1",
            nextVersionIdMarker = "v-1",
          ),
          listPage(versions = listOf(version("checkin-$u2/1", "v-2"))),
        )
      on { deleteObjects(any<DeleteObjectsRequest>()) } doReturn DeleteObjectsResponse.builder().build()
    }

    job(s3).cleanupCheckinFile("1")

    val listCaptor = argumentCaptor<ListObjectVersionsRequest>()
    verify(s3, times(2)).listObjectVersions(listCaptor.capture())
    assertThat(listCaptor.firstValue.keyMarker()).isNull()
    assertThat(listCaptor.firstValue.versionIdMarker()).isNull()
    assertThat(listCaptor.secondValue.keyMarker()).isEqualTo("checkin-$u1/1")
    assertThat(listCaptor.secondValue.versionIdMarker()).isEqualTo("v-1")

    assertThat(captureDeletedKeys(s3))
      .containsExactlyInAnyOrder("checkin-$u1/1" to "v-1", "checkin-$u2/1" to "v-2")
  }

  @Test
  fun `deleteObjects is batched in chunks of 1000`() {
    val versions = (1..1001).map { i ->
      version("checkin-${UUID(0L, i.toLong())}/1", "v-$i")
    }
    val s3: S3Client = mock {
      on { listObjectVersions(any<ListObjectVersionsRequest>()) } doReturn listPage(versions = versions)
      on { deleteObjects(any<DeleteObjectsRequest>()) } doReturn DeleteObjectsResponse.builder().build()
    }

    job(s3).cleanupCheckinFile("1")

    val captor = argumentCaptor<DeleteObjectsRequest>()
    verify(s3, times(2)).deleteObjects(captor.capture())
    assertThat(captor.firstValue.delete().objects()).hasSize(1000)
    assertThat(captor.secondValue.delete().objects()).hasSize(1)
  }

  // ---- end-to-end (process) ---------------------------------------------------------------

  @Test
  fun `process runs cleanup for both legacy filenames in one job-log entry`() {
    val uuid = "33333333-4444-5555-6666-777777777777"
    val s3: S3Client = mock {
      on { listObjectVersions(any<ListObjectVersionsRequest>()) } doReturn listPage(
        versions = listOf(
          version("checkin-$uuid/0"),
          version("checkin-$uuid/1", "img-v"),
          version("checkin-$uuid/video", "vid-v"),
        ),
      )
      on { deleteObjects(any<DeleteObjectsRequest>()) } doReturn DeleteObjectsResponse.builder().build()
    }

    job(s3).process()

    verify(s3, times(2)).listObjectVersions(any<ListObjectVersionsRequest>())
    val deleteCaptor = argumentCaptor<DeleteObjectsRequest>()
    verify(s3, times(2)).deleteObjects(deleteCaptor.capture())
    val deletedKeys = deleteCaptor.allValues.flatMap { it.delete().objects().map { o -> o.key() } }
    assertThat(deletedKeys).containsExactlyInAnyOrder("checkin-$uuid/1", "checkin-$uuid/video")
  }

  @Test
  fun `job log entry is opened on start and closed on completion`() {
    val s3: S3Client = mock {
      on { listObjectVersions(any<ListObjectVersionsRequest>()) } doReturn listPage()
    }
    val captor = argumentCaptor<JobLogV2>()
    whenever(jobLogRepository.saveAndFlush(captor.capture())) doAnswer { it.arguments[0] as JobLogV2 }

    job(s3).process()

    assertThat(captor.allValues).hasSize(2)
    assertThat(captor.firstValue).isSameAs(captor.secondValue)
    assertThat(captor.firstValue.jobType).isEqualTo("V2_CHECKIN_LEGACY_CLEANUP")
    assertThat(captor.secondValue.endedAt).isNotNull()
  }
}
