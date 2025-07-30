package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.springframework.http.HttpStatus
import org.springframework.validation.BindingResult
import org.springframework.web.server.ResponseStatusException

internal fun intoResponseStatusException(bindingResult: BindingResult): ResponseStatusException {
  val errors = bindingResult.fieldErrors.associateBy({ it.field }, { it.defaultMessage })
  return ResponseStatusException(HttpStatus.BAD_REQUEST, errors.toString())
}
