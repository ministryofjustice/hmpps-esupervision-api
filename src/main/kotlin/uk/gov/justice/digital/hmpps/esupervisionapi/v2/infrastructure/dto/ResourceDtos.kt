package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto

import java.net.URL

data class LocationInfo(
  val url: URL,
  val contentType: String,
  /**
   * How long the `url` will be valid for.
   */
  val duration: String,
)

/**
 * Note, only one of `locationInfo` or `locations` should be non-null.
 */
data class UploadLocationResponse(
  val locationInfo: LocationInfo?,
  val locations: List<LocationInfo>? = null,
  val errorMessage: String? = null,
)
