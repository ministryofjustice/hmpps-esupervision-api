package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import jakarta.validation.constraints.NotBlank
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

data class CheckinUploadLocationResponse(
  val references: List<LocationInfo>? = null,
  val snapshots: List<LocationInfo>? = null,
  val video: LocationInfo? = null,
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
  @JsonDeserialize(using = LocalDateDeserializer::class) val dueDate: LocalDate,
)

/**
 * Submitted on behalf of the practitioner as a review of the checkin.
 */
data class CheckinReviewRequest(
  @field:NotBlank
  val practitioner: String,
  val manualIdCheck: ManualIdVerificationResult,
)
