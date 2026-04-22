package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SurveyVersion

/**
 * See [formatSurvey]
 */
fun StringBuilder.appendQuestionsAndAnswers(survey: Map<String, Any>): StringBuilder = formatSurvey(survey, this)

/**
 * Formats the offender's response to a survey into a human-readable string
 * that will be displayed as a note in NDelius.
 */
fun formatSurvey(survey: Map<String, Any>, sb: StringBuilder): StringBuilder {
  sb.appendLine("Check in answers:")
  formatSurveyResponseHumanReadable(survey).forEach {
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

private fun formatSurveyResponseHumanReadable(survey: Map<String, Any>): List<String> {
  val lines = mutableListOf<String>()

  // Custom labels for known fields
  val customLabels = mapOf(
    "mentalHealth" to "How they have been feeling",
    "mentalHealthComment" to "What they want us to know about how they have been feeling",
    "assistance" to "Anything they need support with or to let us know",
    "mentalHealthSupport" to "What they want us to know about mental health",
    "alcoholSupport" to "What they want us to know about alcohol",
    "drugsSupport" to "What they want us to know about drugs",
    "moneySupport" to "What they want us to know about money",
    "housingSupport" to "What they want us to know about housing",
    "employmentEduSupport" to "What they want us to know about employment and education",
    "supportSystemSupport" to "What they want us to know about their relationships",
    "otherSupport" to "What they want us to know about (something else)",
    "callback" to "If they need us to contact them before their next appointment",
    "callbackDetails" to "What they want to talk about",
  )

  for ((key, value) in customLabels) {
    val surveyValue = survey[key]

    // Skip null or empty values
    if (surveyValue == null) continue
    if (surveyValue is String && surveyValue.isBlank()) continue
    if (surveyValue is List<*> && surveyValue.isEmpty()) continue
    if (surveyValue is Map<*, *> && surveyValue.isEmpty()) continue

    // Format the value based on its type
    val formattedValue = formatValue(surveyValue)

    // Skip if formatted value is empty
    if (formattedValue.isBlank()) continue

    lines.add("$value: $formattedValue")
  }

  return lines
}

@Suppress("UNCHECKED_CAST")
private fun formatValue(value: Any?, indent: Int = 0): String {
  if (value == null) return ""

  return when (value) {
    is String -> formatStringValue(value)
    is Boolean -> if (value) "Yes" else "No"
    is Number -> value.toString()
    is List<*> -> formatListValue(value, indent)
    is Map<*, *> -> formatMapValue(value as Map<String, Any>, indent)
    else -> value.toString()
  }
}

/**
 * Format string values - convert enums and special values to readable text
 */
private fun formatStringValue(value: String): String {
  if (value.isBlank()) return ""

  // Common enum patterns to human-readable
  return when {
    // Yes/No values
    value.equals("YES", ignoreCase = true) -> "Yes"
    value.equals("NO", ignoreCase = true) -> "No"
    // NO_HELP special case
    value == "NO_HELP" -> "No, I don't need any support"
    // SCREAMING_SNAKE_CASE to Title Case
    value == value.uppercase() -> {
      value.lowercase().split("_").joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
      }
    }
    // Already readable
    else -> value
  }
}

/**
 * Format list values
 */
private fun formatListValue(list: List<*>, indent: Int): String {
  val filteredList = list.filterNotNull()
    .map { formatValue(it, indent) }
    .filter { it.isNotBlank() && it != "No help" }

  if (filteredList.isEmpty()) return ""
  if (filteredList.size == 1) return filteredList.first()

  return filteredList.joinToString(", ")
}

/**
 * Format map/object values as key: value pairs
 */
private fun formatMapValue(map: Map<String, Any>, indent: Int): String {
  if (map.isEmpty()) return ""

  val indentStr = "  ".repeat(indent + 1)
  val entries = map.entries
    .filter { (_, v) -> v != null }
    .mapNotNull { (k, v) ->
      val formattedValue = formatValue(v, indent + 1)
      if (formattedValue.isBlank()) {
        null
      } else {
        "$indentStr${camelCaseToHumanReadable(k)}: $formattedValue"
      }
    }

  if (entries.isEmpty()) return ""

  return "\n" + entries.joinToString("\n")
}

/**
 * Convert camelCase to Human Readable
 * e.g., "mentalHealthSupport" -> "Mental health support"
 */
private fun camelCaseToHumanReadable(camelCase: String): String {
  if (camelCase.isBlank()) return ""

  val result = StringBuilder()
  for ((index, char) in camelCase.withIndex()) {
    if (char.isUpperCase() && index > 0) {
      result.append(' ')
      result.append(char.lowercase())
    } else if (index == 0) {
      result.append(char.uppercase())
    } else {
      result.append(char)
    }
  }
  return result.toString()
}
