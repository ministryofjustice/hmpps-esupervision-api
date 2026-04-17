package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SurveyVersion

class SurveyDetailsTest {

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

    val result = formatSurvey(survey, StringBuilder()).toString()
    assertTrue(result.contains("What is your favourite colour?: Blue"))
    assertTrue(result.contains("What is your favourite animal?: Dog"))
  }

  @Test
  fun `render survey with no custom questions`() {
    val result = formatSurvey(exampleSurvey, StringBuilder()).toString()
    assertFalse(result.contains("Custom questions"))
  }

  @Test
  fun `render survey for pre-questions version`() {
    val survey = exampleSurvey.toMutableMap()
    survey["version"] = SurveyVersion.V20250710pilot.version
    survey["customQuestions"] = listOf(
      mapOf("question" to "What is your favourite colour?", "response" to "Blue"),
    )
    val result = formatSurvey(survey, StringBuilder()).toString()
    assertFalse(result.contains("Blue"))
  }
}

private val exampleSurvey: Map<String, Any> = mapOf(
  "version" to SurveyVersion.V20260416Questions.version,
  "mentalHealth" to "WELL",
  "housingSupport" to "NO_HELP",
)
