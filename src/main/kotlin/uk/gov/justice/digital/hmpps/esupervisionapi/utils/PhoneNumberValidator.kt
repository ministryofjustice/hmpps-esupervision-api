package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [PhoneNumberValidator::class])
annotation class PhoneNumber(
  val message: String = "Must be a valid phone number.",
  val groups: Array<KClass<*>> = [],
  val payload: Array<KClass<out Payload>> = [],
)

class PhoneNumberValidator : ConstraintValidator<PhoneNumber, String> {
  override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
    if (value != null) {
      val phoneNumUtil = PhoneNumberUtil.getInstance()
      try {
        val number = phoneNumUtil.parse(value, "GB")
        return phoneNumUtil.isValidNumber(number)
      } catch (_: NumberParseException) {
        return false
      }
    }

    return false
  }
}
