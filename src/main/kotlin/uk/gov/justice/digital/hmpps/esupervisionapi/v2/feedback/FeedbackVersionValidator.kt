package uk.gov.justice.digital.hmpps.esupervisionapi.v2.feedback

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.feedback.RequiresVersion

class FeedbackVersionValidator : ConstraintValidator<RequiresVersion, Map<String, Any>> {

  override fun isValid(
    value: Map<String, Any>?,
    context: ConstraintValidatorContext,
  ): Boolean {
    if (value == null) return false

    val version = value["version"]
    return version is Number && version.toInt() > 0
  }
}
