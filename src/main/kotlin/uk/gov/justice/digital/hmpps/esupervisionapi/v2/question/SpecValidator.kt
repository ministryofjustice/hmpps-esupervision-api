package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import jakarta.validation.ConstraintValidatorContext
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionResponseFormat

fun validateSpec(responseFormat: QuestionResponseFormat, spec: Map<String, Any>, context: ConstraintValidatorContext?): ValidationResult {
  val result = validateSpecInternal(responseFormat, spec, context)
  if (!result.isValid && result.message != null) {
    context?.constraint(result.message)
  }
  return result
}

private fun validateSpecInternal(responseFormat: QuestionResponseFormat, spec: Map<String, Any>, context: ConstraintValidatorContext?): ValidationResult {
  if (!validateSpecPlaceholders(spec)) {
    return ValidationResult(false, "placeholders must be a list of strings")
  }

  return when (responseFormat) {
    QuestionResponseFormat.TEXT -> {
      ValidationResult(true)
    }
    QuestionResponseFormat.SINGLE_CHOICE -> {
      validateSingleChoice(spec, context)
    }
    QuestionResponseFormat.MULTIPLE_CHOICE -> {
      validateMultipleChoice(spec, context)
    }
  }
}

private fun validateSpecPlaceholders(spec: Map<String, Any>): Boolean {
  val placeholders = spec["placeholders"] ?: return true
  if (placeholders is List<*>) {
    return placeholders.all { it is String }
  }
  return false
}

private fun validateSpecCommon(spec: Map<String, Any>, context: ConstraintValidatorContext?): ValidationResult {
  val detailsLabel = spec["details_label"]
  if (detailsLabel != null && detailsLabel !is String) {
    return ValidationResult(false, "invalid spec: details_label must be a string or null")
  }

  return ValidationResult(true)
}

fun validateSingleChoice(spec: Map<String, Any>, context: ConstraintValidatorContext?): ValidationResult {
  val result = validateSpecCommon(spec, context)
  if (!result.isValid) return result
  val choices = spec["choices"]
  return validateChoices(choices, context)
}

fun validateMultipleChoice(spec: Map<String, Any>, context: ConstraintValidatorContext?): ValidationResult {
  val result = validateSpecCommon(spec, context)
  if (!result.isValid) return result
  val choices = spec["choices"]
  return validateChoices(choices, context)
}

private fun validateChoices(choices: Any?, context: ConstraintValidatorContext?): ValidationResult {
  if (choices is List<*>) {
    for (i in 0..<choices.size) {
      val choice = choices[i]
      if (choice is Map<*, *>) {
        val result = validateChoice(choice, i, context)
        if (!result.isValid) return result
      } else {
        return ValidationResult(false, "invalid spec: choice must be a map (position ${i + 1})")
      }
    }
  } else {
    return ValidationResult(false, "invalid spec: choices must be an array")
  }
  return ValidationResult(true)
}

private fun validateChoice(choice: Map<*, *>, i: Int, context: ConstraintValidatorContext?): ValidationResult {
  val id = choice["id"]
  if (id !is String) {
    return ValidationResult(false, "invalid spec: choice id must be a string (position ${i + 1})")
  }
  val label = choice["label"]
  if (label !is String) {
    return ValidationResult(false, "invalid spec: choice label must be a string (position ${i + 1})")
  }
  val detailsLabel = choice["details_label"]
  if (detailsLabel != null && detailsLabel !is String) {
    return ValidationResult(false, "invalid spec: choice details_label must be a string or null (position ${i + 1})")
  }
  val detailsId = choice["details_id"]
  if (detailsId != null && detailsId !is String) {
    return ValidationResult(false, "invalid spec: choice details_id must be a string or null (position ${i + 1})")
  }
  val domainMsgHeader = choice["domain_msg_head"]
  if (domainMsgHeader != null && domainMsgHeader !is String) {
    return ValidationResult(false, "invalid spec: choice domain_msg_head must be a string or null (position ${i + 1})")
  }
  return ValidationResult(true)
}
