package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CheckinNoteExportToolTest {

  private val surveyConfig = loadSurveyConfigFromApplicationYml()

  private val survey = mapOf<String, Any>(
    "version" to "2026-04-16@questions",
    "mentalHealth" to "WELL",
    "assistance" to listOf("SUPPORT_SYSTEM", "EMPLOYMENT_EDU", "OTHER"),
    "housingSupport" to "NO_HELP",
    "callback" to "NO",
    "customQuestions" to listOf(mapOf("question" to "Anything else?", "response" to " Blue ", "details" to "Sky")),
  )

  private val legacy = """
    Check in answers:
    How they have been feeling: Well

    Anything they need support with or to let us know: Support System, Employment Edu, Other

    What they want us to know about housing: No, I don't need any support

    If they need us to contact them before their next appointment: No

    Anything else?: Blue
    Details: Sky
  """.trimIndent()

  private fun configFormatted(other: String) = """
    Check in answers:
    How they have been feeling: Well

    Anything they need support with or to let us know: Relationships (family, friends, partner), Employment and education, $other

    What they want us to know about housing: No, I do not need any support

    If they need us to contact them before their next appointment: No

    Anything else?: Blue
    Details: Sky
  """.trimIndent()

  @Test
  fun `before the config formatter deploy uses the frozen legacy wording`() {
    assertThat(formatAnswers(survey, CONFIG_EXPANSIONS_AT.minusMillis(1), surveyConfig)).isEqualTo(legacy)
  }

  @Test
  fun `from the config formatter deploy until the OTHER expansion, OTHER is prettified`() {
    assertThat(formatAnswers(survey, CONFIG_EXPANSIONS_AT, surveyConfig)).isEqualTo(configFormatted("Other"))
    assertThat(formatAnswers(survey, OTHER_EXPANSION_AT.minusMillis(1), surveyConfig)).isEqualTo(configFormatted("Other"))
  }

  @Test
  fun `from the OTHER expansion deploy, OTHER is expanded`() {
    assertThat(formatAnswers(survey, OTHER_EXPANSION_AT, surveyConfig)).isEqualTo(configFormatted("Something else"))
  }

  @Test
  fun `unknown submission time uses the current formatter`() {
    assertThat(formatAnswers(survey, null, surveyConfig)).isEqualTo(configFormatted("Something else"))
  }
}
