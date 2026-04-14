package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AssignCustomQuestionsRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CustomQuestionItem
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionTemplateDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.placeholders
import kotlin.reflect.KClass

@Component
class QuestionParamsValidator : ConstraintValidator<ValidQuestionParams, AssignCustomQuestionsRequest> {
  override fun isValid(value: AssignCustomQuestionsRequest?, context: ConstraintValidatorContext): Boolean {
    if (value == null) return false
    if (value.questions.isEmpty()) {
      context.constraint("Missing questions")
      return false
    }
    if (value.questions.size > 3) {
      context.constraint("Up to three question allowed")
      return false
    }

    val questions = value.questions
    var valid = true
    for (i in 0..<questions.size) {
      valid = valid && preValidateParams(questions[i], i, context)
    }
    return valid
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
 * A high-level validation pass. Allows us to quickly reject obviously invalid input.
 *
 * Note: proper validation requires us to have the `responseSpec` of a question (see [validateAgainstTemplates]).
 * This means that even when we return true, the question may not be valid
 *
 * @param question
 * @param i question index (for error messages
 * @param context
 */
fun preValidateParams(
  question: CustomQuestionItem,
  i: Int = 1,
  context: ConstraintValidatorContext?,
): Boolean {
  if (!validatePlaceholders(question.params)) {
    context?.constraint("Invalid placeholders for question ${i + 1}")
    return false
  }
  return true
}

@Target(AnnotationTarget.CLASS, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [QuestionParamsValidator::class])
annotation class ValidQuestionParams(
  val message: String = "Invalid question parameters",
  val groups: Array<KClass<*>> = [],
  val payload: Array<KClass<out Payload>> = [],
)

fun validateAgainstTemplates(item: CustomQuestionItem, template: QuestionTemplateDto) {
  if (item.params.containsKey("placeholders")) {
    val paramsPlaceholders = (item.params["placeholders"] as Map<String, String>).keys
    val templatePlaceholders = template.placeholders().toSet()
    if (paramsPlaceholders != templatePlaceholders) {
      throw BadArgumentException("Question ${item.id} has invalid placeholders: $paramsPlaceholders. Expected: $templatePlaceholders")
    }
  }
}
