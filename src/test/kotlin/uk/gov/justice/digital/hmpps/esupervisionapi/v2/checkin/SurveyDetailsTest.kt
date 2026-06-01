package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.config.SurveyValueExpansionsConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SurveyVersion

class SurveyDetailsTest {

  val surveyConfig = SurveyValueExpansionsConfig(
    mapOf(
      "WELL" to "Very well",
      "NOT_WELL" to "Not well",
      "NO_HELP" to "No help",
      "HELP" to "Help",
      "NOT_SURE" to "Not sure",
    ),
    mapOf(
      "mentalHealth" to "What they want us to know about mental health",
      "housingSupport" to "What they want us to know about housing",
    ),
  )

  @Test
  fun `render survey with custom questions`() {
    val survey = mapOf(
      "version" to SurveyVersion.V20260416Questions.version,
      "mentalHealth" to "WELL",
      "housingSupport" to "NO_HELP",
      "customQuestions" to listOf(
        mapOf("question" to "What is your favourite colour?", "response" to "Blue"),
        mapOf("question" to "What is your favourite animal?", "response" to "Dog"),
      ),
    )

    val result = formatSurvey(survey, StringBuilder(), surveyConfig).toString()
    assertTrue(result.contains("What they want us to know about mental health: Very well"))
    assertTrue(result.contains("What they want us to know about housing: No help"))
    assertTrue(result.contains("What is your favourite colour?: Blue"))
    assertTrue(result.contains("What is your favourite animal?: Dog"))
  }

  @Test
  fun `render survey with no custom questions`() {
    val result = formatSurvey(exampleSurvey, StringBuilder(), surveyConfig).toString()
    assertFalse(result.contains("Custom questions"))
  }

  @Test
  fun `render survey for pre-questions version`() {
    val survey = exampleSurvey.toMutableMap()
    survey["version"] = SurveyVersion.V20250710pilot.version
    survey["customQuestions"] = listOf(
      mapOf("question" to "What is your favourite colour?", "response" to "Blue"),
    )
    val result = formatSurvey(survey, StringBuilder(), surveyConfig).toString()
    assertFalse(result.contains("Blue"))
  }
}

private val exampleSurvey: Map<String, Any> = mapOf(
  "version" to SurveyVersion.V20260416Questions.version,
  "mentalHealth" to "WELL",
  "housingSupport" to "NO_HELP",
)
