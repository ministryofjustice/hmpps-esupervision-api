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
    "employmentEduSupport" to "Looking for work",
    "supportSystemSupport" to "Family",
    "callback" to "NO",
    "customQuestions" to listOf(mapOf("question" to "Anything else?", "response" to " Blue ", "details" to "Sky")),
  )

  private val customQuestionsBlock = "\n\nAnything else?: Blue\nDetails: Sky"

  private val preRelationships = """
    Check in answers:
    How they have been feeling: Well

    Anything they need support with or to let us know: Support System, Employment Edu, Other

    What they want us to know about housing: No, I don't need any support

    What they want us to know about their support system: Family

    If they need us to contact them before their next appointment: No
  """.trimIndent()

  private val legacy = """
    Check in answers:
    How they have been feeling: Well

    Anything they need support with or to let us know: Support System, Employment Edu, Other

    What they want us to know about housing: No, I don't need any support

    What they want us to know about employment and education: Looking for work

    What they want us to know about their relationships: Family

    If they need us to contact them before their next appointment: No
  """.trimIndent() + customQuestionsBlock

  private fun configFormatted(other: String) = """
    Check in answers:
    How they have been feeling: Well

    Anything they need support with or to let us know: Relationships (family, friends, partner), Employment and education, $other

    What they want us to know about housing: No, I do not need any support

    What they want us to know about employment and education: Looking for work

    What they want us to know about their relationships: Family

    If they need us to contact them before their next appointment: No
  """.trimIndent() + customQuestionsBlock

  @Test
  fun `before the custom questions deploy, old labels and no custom questions`() {
    assertThat(formatAnswers(survey, CUSTOM_QUESTIONS_AT.minusMillis(1), surveyConfig)).isEqualTo(preRelationships)
  }

  @Test
  fun `from the custom questions deploy until the relationships deploy, old labels with custom questions`() {
    val expected = preRelationships + customQuestionsBlock
    assertThat(formatAnswers(survey, CUSTOM_QUESTIONS_AT, surveyConfig)).isEqualTo(expected)
    assertThat(formatAnswers(survey, RELATIONSHIPS_AT.minusMillis(1), surveyConfig)).isEqualTo(expected)
  }

  @Test
  fun `from the relationships deploy until the config formatter deploy, the frozen legacy wording`() {
    assertThat(formatAnswers(survey, RELATIONSHIPS_AT, surveyConfig)).isEqualTo(legacy)
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

  @Test
  fun `warns about answers held but not rendered at the time`() {
    val before = CUSTOM_QUESTIONS_AT.minusMillis(1)
    assertThat(warningsFor(formatAnswers(survey, before, surveyConfig), survey, before))
      .anyMatch { it.contains("customQuestions") }
      .anyMatch { it.contains("employmentEduSupport") }
    assertThat(warningsFor(formatAnswers(survey, RELATIONSHIPS_AT, surveyConfig), survey, RELATIONSHIPS_AT)).isEmpty()
  }
}
