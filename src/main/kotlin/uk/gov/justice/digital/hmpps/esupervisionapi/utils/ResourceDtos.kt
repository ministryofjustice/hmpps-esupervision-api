package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.springframework.data.domain.Pageable
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.ManualIdVerificationResult
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

/**
 * Note, only one of `locationInfo` or `locations` should be non-null.
 */
data class UploadLocationResponse(
  val locationInfo: LocationInfo?,
  val locations: List<LocationInfo>? = null,
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
  val practitioner: String,
  val offender: UUID,
  val questions: String,
  @JsonDeserialize(using = LocalDateDeserializer::class) val dueDate: LocalDate,
)

/**
 * Submitted on behalf of the practitioner as a review of the checkin.
 */
data class CheckinReviewRequest(
  val practitioner: String,
  val manualIdCCheck: ManualIdVerificationResult,
)
