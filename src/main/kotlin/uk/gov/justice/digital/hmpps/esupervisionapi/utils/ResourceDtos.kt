package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.springframework.data.domain.Pageable

data class Pagination(
  val pageNumber: Int,
  val pageSize: Int,
)

fun Pageable.toPagination(): Pagination = Pagination(pageNumber, pageSize)
