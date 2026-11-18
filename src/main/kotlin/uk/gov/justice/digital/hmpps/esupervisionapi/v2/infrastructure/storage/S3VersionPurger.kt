package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier

/** Keys whose every version and delete marker was removed, and keys S3 refused to delete. */
data class S3PurgeResult(val keysDeleted: Int, val keysFailed: Int)

/** Deliberately outside the ordinary S3 circuit breaker and retry: cleanup has separate permissions. */
@Component
class S3VersionPurger(@Qualifier("MOJ") private val s3Client: S3Client) {
  fun purgeAllVersions(bucket: String, prefix: String, matches: (String) -> Boolean): S3PurgeResult {
    val targets = mutableListOf<ObjectIdentifier>()
    var keyMarker: String? = null
    var versionIdMarker: String? = null
    do {
      val page = s3Client.listObjectVersions(
        ListObjectVersionsRequest.builder()
          .bucket(bucket)
          .prefix(prefix)
          .keyMarker(keyMarker)
          .versionIdMarker(versionIdMarker)
          .build(),
      )
      page.versions().filter { matches(it.key()) }
        .mapTo(targets) { ObjectIdentifier.builder().key(it.key()).versionId(it.versionId()).build() }
      page.deleteMarkers().filter { matches(it.key()) }
        .mapTo(targets) { ObjectIdentifier.builder().key(it.key()).versionId(it.versionId()).build() }
      keyMarker = page.nextKeyMarker()
      versionIdMarker = page.nextVersionIdMarker()
    } while (page.isTruncated == true)

    val failedKeys = mutableSetOf<String>()
    // DeleteObjects takes at most 1000 identifiers. Continue even when an entire batch fails.
    targets.chunked(1000).forEach { chunk ->
      try {
        val response = s3Client.deleteObjects(
          DeleteObjectsRequest.builder()
            .bucket(bucket)
            .delete(Delete.builder().objects(chunk).quiet(true).build())
            .build(),
        )
        response.errors().mapTo(failedKeys) { it.key() }
      } catch (e: Exception) {
        LOG.warn("Could not delete a batch of {} object versions under {}/{}: {}", chunk.size, bucket, prefix, e.javaClass.simpleName)
        chunk.mapTo(failedKeys) { it.key() }
      }
    }
    val keys = targets.map { it.key() }.toSet()
    return S3PurgeResult(keysDeleted = (keys - failedKeys).size, keysFailed = failedKeys.size)
  }

  companion object {
    private val LOG = LoggerFactory.getLogger(S3VersionPurger::class.java)
  }
}
