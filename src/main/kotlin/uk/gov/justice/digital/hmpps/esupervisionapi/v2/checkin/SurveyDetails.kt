package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import uk.gov.justice.digital.hmpps.esupervisionapi.config.SurveyValueExpansionsConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SurveyVersion

/**
 * See [formatSurvey]
 */
fun StringBuilder.appendQuestionsAndAnswers(survey: Map<String, Any>, surveyConfig: SurveyValueExpansionsConfig?): StringBuilder = formatSurvey(survey, this, surveyConfig)

/**
 * Formats the offender's response to a survey into a human-readable string
 * that will be displayed as a note in NDelius.
 */
fun formatSurvey(survey: Map<String, Any>, sb: StringBuilder, surveyConfig: SurveyValueExpansionsConfig?): StringBuilder {
  sb.appendLine("Check in answers:")
  formatSurveyResponseHumanReadable(survey, surveyConfig).forEach {
    sb.appendLine(it)
    sb.appendLine()
  }

  val version = survey["version"]
  if (version == SurveyVersion.V20260416Questions.version) {
    // customQuestions: Array<{ question: string; response: string; details?: string }>)
    val customQuestions = (survey["customQuestions"] as? List<Map<String, Any>>) ?: emptyList()
    customQuestions.forEach { customQuestion ->
      val question = customQuestion["question"]
      val response = (customQuestion["response"] ?: "") as String
      sb.appendLine("$question: ${response.trim()}")
      val details = (customQuestion["details"] ?: "") as String
      if (details.isNotBlank()) {
        sb.appendLine("Details: ${details.trim()}")
      }
      sb.appendLine()
    }
  }
  return sb
}

private fun formatSurveyResponseHumanReadable(survey: Map<String, Any>, surveyConfig: SurveyValueExpansionsConfig?): List<String> {
  val lines = mutableListOf<String>()
  val formValuesExpansions = surveyConfig?.expansions ?: emptyMap()
  val customLabels = surveyConfig?.customLabels ?: emptyMap()
  for ((key, value) in customLabels) {
    val surveyValue = survey[key] ?: continue

    if (surveyValue is String && surveyValue.isBlank()) continue
    if (surveyValue is List<*> && surveyValue.isEmpty()) continue

    val formattedValueNew: List<String> = when (surveyValue) {
      is String -> listOf(surveyValue)
      is Boolean -> listOf(if (surveyValue) "YES" else "NO")
      is Number -> listOf(value)
      is List<*> -> surveyValue.filter { it is String && it.isNotBlank() }.map { it as String }
      else -> listOf(surveyValue.toString())
    }
    if (formattedValueNew.isEmpty()) continue
    val formatted = formattedValueNew.map { formValuesExpansions.getOrElse(it) { maybePrettifySnakeCase(it) } }

    lines.add("$value: ${formatted.joinToString(", ")}")
  }

  return lines
}

private fun maybePrettifySnakeCase(value: String): String = if (value == value.uppercase()) prettifySnakeCase(value) else value

private fun prettifySnakeCase(value: String): String = value
  .lowercase()
  .split("_")
  .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
