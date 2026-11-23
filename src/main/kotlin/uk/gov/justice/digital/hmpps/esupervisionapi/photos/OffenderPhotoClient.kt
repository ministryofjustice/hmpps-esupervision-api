package uk.gov.justice.digital.hmpps.esupervisionapi.photos

import java.net.URL
import java.time.Duration
import java.util.UUID

/** Storage for the offender's setup/reference photo, not check-in media. */
interface OffenderPhotoClient {
  fun presignUpload(offenderUuid: UUID, contentType: String, duration: Duration, checksumSha256Base64: String): OffenderPhotoUpload

  /** Signs a read URL without checking whether the photo exists. */
  fun presignRead(offenderUuid: UUID): URL

  fun isUploaded(offenderUuid: UUID): Boolean

  fun referenceLocation(offenderUuid: UUID): ReferencePhotoLocation

  fun purge(offenderUuid: UUID): OffenderPhotoPurgeResult
}

data class OffenderPhotoUpload(val url: URL, val requiredHeaders: Map<String, String>)

/** Temporary storage seam for facial verification. */
data class ReferencePhotoLocation(val bucket: String, val key: String)

data class OffenderPhotoPurgeResult(val keysDeleted: Int, val keysFailed: Int)
