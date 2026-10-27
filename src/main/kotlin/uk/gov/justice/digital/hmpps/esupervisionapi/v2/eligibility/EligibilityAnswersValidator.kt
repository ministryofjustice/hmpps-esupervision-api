package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EligibilityAnswers
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [EligibilityAnswersValidator::class])
annotation class ValidEligibilityAnswers(
  val message: String = "accreditedProgramme and youthSentence must either both be null or both be non-null",
  val groups: Array<KClass<*>> = [],
  val payload: Array<KClass<out Payload>> = [],
)

class EligibilityAnswersValidator : ConstraintValidator<ValidEligibilityAnswers, EligibilityAnswers> {
  override fun isValid(value: EligibilityAnswers?, context: ConstraintValidatorContext): Boolean {
    if (value == null || (value.accreditedProgramme == null) == (value.youthSentence == null)) {
      return true
    }

    context.disableDefaultConstraintViolation()
    context.buildConstraintViolationWithTemplate(context.defaultConstraintMessageTemplate)
      .addPropertyNode("youthSentence")
      .addConstraintViolation()
    return false
  }
}
