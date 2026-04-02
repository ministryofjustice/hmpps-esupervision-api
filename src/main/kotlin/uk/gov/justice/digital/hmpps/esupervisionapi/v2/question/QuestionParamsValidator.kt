package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AssignCustomQuestionsRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CustomQuestionItem
import kotlin.reflect.KClass

@Component
class QuestionParamsValidator : ConstraintValidator<ValidParams, AssignCustomQuestionsRequest> {
  override fun isValid(value: AssignCustomQuestionsRequest?, context: ConstraintValidatorContext): Boolean {
    if (value == null) return false
    if (value.questions.isEmpty()) {
      context.constraint("Missing questions")
      return false
    }
    val questions = value.questions
    for (i in 0..<questions.size) {
      return validateParams(questions[i], i, context)
    }
    return true
  }
}

internal fun ConstraintValidatorContext.constraint(message: String) {
  this.disableDefaultConstraintViolation()
  this.buildConstraintViolationWithTemplate(message)
    .addConstraintViolation()
}

fun validatePlaceholders(params: Map<String, Any>): Boolean {
  val placeholders = params["placeholders"] ?: return true
  if (placeholders is Map<*, *>) {
    return placeholders.all { it.key is String && it.value is String }
  }
  return false
}

/**
 * @param question
 * @param i question index (for error messages
 * @param context
 */
fun validateParams(
  question: CustomQuestionItem,
  i: Int = 1,
  context: ConstraintValidatorContext?,
): Boolean {
  if (!validatePlaceholders(question.params)) {
    context?.constraint("Invalid placeholders for question ${i + 1}")
    return false
  }
  when (val format = question.params["responseFormat"]) {
    "TEXT" -> {
      return true
    }

    "MULTIPLE_CHOICE" -> {
      return true
    }

    "SINGLE_CHOICE" -> {
      return true
    }

    else -> {
      context?.constraint("Invalid responseFormat for question ${i + 1}: '$format'")
      return false
    }
  }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [QuestionParamsValidator::class])
annotation class ValidParams(
  val message: String = "Invalid question parameters",
  val groups: Array<KClass<*>> = [],
  val payload: Array<KClass<out Payload>> = [],
)
