package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.springframework.data.domain.Pageable
import java.net.URL
import java.time.LocalDate
import java.util.UUID

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

data class CollectionDto<ElemDto>(
  val pagination: Pagination,
  val content: List<ElemDto>,
)

/**
 * Everything we need to create a checkin
 */
data class CreateCheckinRequest(
  val practitioner: UUID,
  val offender: UUID,
  val questions: String,
  val dueDate: LocalDate,
)
