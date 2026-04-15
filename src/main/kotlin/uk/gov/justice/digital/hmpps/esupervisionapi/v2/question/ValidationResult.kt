package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

data class ValidationResult(
  val isValid: Boolean,
  val message: String? = null,
)
