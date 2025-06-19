package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.springframework.data.domain.Pageable
import java.net.URL

data class Pagination(
  val pageNumber: Int,
  val pageSize: Int,
)

fun Pageable.toPagination(): Pagination = Pagination(pageNumber, pageSize)

data class LocationInfo(
  val url: URL,
  val contentType: String,
  val duration: String,
)

data class UploadLocationResponse(
  val locationInfo: LocationInfo?,
  val errorMessage: String? = null,
)
