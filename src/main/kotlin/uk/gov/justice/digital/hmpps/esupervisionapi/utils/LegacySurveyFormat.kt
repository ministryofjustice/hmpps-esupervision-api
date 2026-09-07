package uk.gov.justice.digital.hmpps.esupervisionapi.utils

/**
 * A FROZEN copy of the survey formatter as it stood before commit e948cf35 ("ESUP-1689: simplify
 * survey details formatting"), which reached production on 2026-05-07T10:02:58Z.
 *
 * Used only by [CheckinNoteExportToolKt] to rebuild NDelius contact notes for check-ins submitted
 * before that deploy (ESUP-1956). Those notes were rendered by this code, so reproducing them
 * needs this code — the current formatter would produce different text for several answer values:
 *
 *   NO_HELP         "No, I don't need any support"  ->  "No, I do not need any support"
 *   SUPPORT_SYSTEM  "Support System"                ->  "Relationships (family, friends, partner)"
 *   EMPLOYMENT_EDU  "Employment Edu"                ->  "Employment and education"
 *
 * Note the apostrophe in the NO_HELP wording is a straight quote, as it was in the original.
 *
 * DO NOT "fix", tidy or refactor anything here, and do not call it from application code. Its only
 * correctness criterion is that it matches what production emitted at the time, warts included —
 * for instance formatListValue's `!= "No help"` filter, which is unreachable because NO_HELP is
 * special-cased before the snake-case branch ever runs.
 *
 * Source: git show e948cf35^:src/main/kotlin/.../v2/checkin/SurveyDetails.kt
 */
internal fun StringBuilder.appendLegacyQuestionsAndAnswers(survey: Map<String, Any>): StringBuilder {
  appendLine("Check in answers:")
  legacyFormatSurveyResponse(survey).forEach {
    appendLine(it)
    appendLine()
  }

  // e948cf35 left this block untouched, but it still has to be rendered here: custom questions
  // shipped 2026-04-17, so check-ins from this era can carry them.
  val version = survey["version"]
  if (version == "2026-04-16@questions") {
    @Suppress("UNCHECKED_CAST")
    val customQuestions = (survey["customQuestions"] as? List<Map<String, Any>>) ?: emptyList()
    customQuestions.forEach { customQuestion ->
      val question = customQuestion["question"]
      val response = (customQuestion["response"] ?: "") as String
      appendLine("$question: ${response.trim()}")
      val details = (customQuestion["details"] ?: "") as String
      if (details.isNotBlank()) {
        appendLine("Details: ${details.trim()}")
      }
      appendLine()
    }
  }
  return this
}

private val LEGACY_CUSTOM_LABELS = mapOf(
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

private fun legacyFormatSurveyResponse(survey: Map<String, Any>): List<String> {
  val lines = mutableListOf<String>()

  for ((key, value) in LEGACY_CUSTOM_LABELS) {
    val surveyValue = survey[key]

    if (surveyValue == null) continue
    if (surveyValue is String && surveyValue.isBlank()) continue
    if (surveyValue is List<*> && surveyValue.isEmpty()) continue
    if (surveyValue is Map<*, *> && surveyValue.isEmpty()) continue

    val formattedValue = legacyFormatValue(surveyValue)
    if (formattedValue.isBlank()) continue

    lines.add("$value: $formattedValue")
  }

  return lines
}

@Suppress("UNCHECKED_CAST")
private fun legacyFormatValue(value: Any?, indent: Int = 0): String {
  if (value == null) return ""

  return when (value) {
    is String -> legacyFormatStringValue(value)
    is Boolean -> if (value) "Yes" else "No"
    is Number -> value.toString()
    is List<*> -> legacyFormatListValue(value, indent)
    is Map<*, *> -> legacyFormatMapValue(value as Map<String, Any>, indent)
    else -> value.toString()
  }
}

private fun legacyFormatStringValue(value: String): String {
  if (value.isBlank()) return ""

  return when {
    value.equals("YES", ignoreCase = true) -> "Yes"
    value.equals("NO", ignoreCase = true) -> "No"
    value == "NO_HELP" -> "No, I don't need any support"
    value == value.uppercase() -> {
      value.lowercase().split("_").joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
      }
    }
    else -> value
  }
}

private fun legacyFormatListValue(list: List<*>, indent: Int): String {
  val filteredList = list.filterNotNull()
    .map { legacyFormatValue(it, indent) }
    .filter { it.isNotBlank() && it != "No help" }

  if (filteredList.isEmpty()) return ""
  if (filteredList.size == 1) return filteredList.first()

  return filteredList.joinToString(", ")
}

private fun legacyFormatMapValue(map: Map<String, Any>, indent: Int): String {
  if (map.isEmpty()) return ""

  val indentStr = "  ".repeat(indent + 1)
  val entries = map.entries
    .filter { (_, v) -> v != null }
    .mapNotNull { (k, v) ->
      val formattedValue = legacyFormatValue(v, indent + 1)
      if (formattedValue.isBlank()) {
        null
      } else {
        "$indentStr${legacyCamelCaseToHumanReadable(k)}: $formattedValue"
      }
    }

  if (entries.isEmpty()) return ""

  return "\n" + entries.joinToString("\n")
}

private fun legacyCamelCaseToHumanReadable(camelCase: String): String {
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
