package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto

import java.net.URL

data class LocationInfo(
  val url: URL,
  val contentType: String,
  /**
   * How long the `url` will be valid for.
   */
  val duration: String,
  /**
   * Headers the client must echo on the PUT for S3 to accept the upload.
   * Populated when the URL was signed with a content hash; null otherwise.
   */
  val requiredHeaders: Map<String, String>? = null,
)

/**
 * Note, only one of `locationInfo` or `locations` should be non-null.
 */
data class UploadLocationResponse(
  val locationInfo: LocationInfo?,
  val locations: List<LocationInfo>? = null,
  val errorMessage: String? = null,
)

/**
 * Optional request body for upload_location endpoints carrying a single file's content hash.
 * Old clients omit the body; the URL is then issued without hash binding.
 */
data class UploadHashRequest(
  /** Lowercase hex SHA-256 of the file bytes (64 chars). */
  val sha256: String? = null,
)
