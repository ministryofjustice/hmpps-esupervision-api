package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListItemDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionResponseFormat
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionTemplateDto

class QuestionTemplateTest {

  @Test
  fun `apply template`() {
    val question = example.evalTemplate()
    assertEquals("Replace this and that", question.question)
  }

  @Test
  fun `applying template when no placeholders`() {
    val item = example.copy(template = example.template.copy(template = "Don't replace this or that"))
    assertEquals("Don't replace this or that", item.evalTemplate().question)
  }

  @Test
  fun `behaviour when placeholders and template string don't match`() {
    val item = example.copy(template = example.template.copy(template = "Some {{unexpected}} variables"))
    assertEquals("Some {{unexpected variables}}", item.evalTemplate().question)
  }
}

private val example = QuestionListItemDto(
  template = QuestionTemplateDto(
    1,
    template = "Replace {{this}} and {{that}}",
    responseFormat = QuestionResponseFormat.TEXT,
    responseSpec = mapOf("placeholders" to listOf("this", "that")),
  ),
  params = mapOf("placeholders" to mapOf("this" to "this", "that" to "that")) as Map<String, Any>,
)
