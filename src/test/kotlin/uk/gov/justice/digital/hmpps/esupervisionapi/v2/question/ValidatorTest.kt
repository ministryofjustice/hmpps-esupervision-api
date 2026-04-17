package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AssignCustomQuestionsRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CustomQuestionItem
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Language
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionTemplateDto

class ValidatorTest : IntegrationTestBase() {
  @Autowired lateinit var validator: Validator

  @Autowired lateinit var debugQuestionsRepository: DebugQuestionsRepository

  @Autowired lateinit var questionRepository: QuestionRepository

  @Test
  fun `AssignCustomQuestionsRequest validator - pass`() {
    val req = assignCustomQuestionsRequest.copy(
      questions = listOf(CustomQuestionItem(1, mapOf("key" to "value"))),
    )
    val result = validator.validate(req)
    assertTrue(result.isEmpty())
  }

  @Test
  fun `AssignCustomQuestionsRequest validator - fail, invalid placeholders`() {
    val req = assignCustomQuestionsRequest.copy(
      questions = listOf(CustomQuestionItem(1, mapOf("placeholders" to "invalid"))),
    )
    val result = validator.validate(req)
    assertTrue(result.isNotEmpty())
  }

  @Test
  fun `AssignCustomQuestionsRequest validator - fail, empty string placeholders`() {
    val req = assignCustomQuestionsRequest.copy(
      questions = listOf(CustomQuestionItem(1, mapOf("placeholders" to mapOf("key" to "")))),
    )
    val result = validator.validate(req)
    assertTrue(result.isNotEmpty())
  }

  @Test
  fun `AssignCustomQuestionsRequest validator - fail, placeholders are not strings`() {
    val req = assignCustomQuestionsRequest.copy(
      questions = listOf(CustomQuestionItem(1, mapOf("placeholders" to listOf(1, 2, 3)))),
    )
    val result = validator.validate(req)
    assertTrue(result.isNotEmpty())
  }

  @Test
  fun `AssignCustomQuestionsRequest validator - pass, valid placeholders`() {
    val req = assignCustomQuestionsRequest.copy(
      questions = listOf(CustomQuestionItem(1, mapOf("placeholders" to mapOf("a" to "b")))),
    )
    val result = validator.validate(req)
    assertTrue(result.isEmpty())
  }

  @Test
  fun `validate default questions have been configured properly`() {
    val items = debugQuestionsRepository.findAllItems()
    for (i in 0..<items.size) {
      val item = items[i]
      val params = item.params
      assertTrue(preValidateParams(CustomQuestionItem(1, params), i, null), "Question $i failed validation")
    }
  }

  @Test
  fun `validate fixed ENGLISH questions specs in the db`() {
    val templates = questionRepository.getFixedQuestionTemplates(Language.ENGLISH)
    assertTrue(templates.isNotEmpty())
    validateFixedQuestions(templates)
  }

  @Test
  fun `validate fixed WELSH questions specs in the db`() {
    val templates = questionRepository.getFixedQuestionTemplates(Language.ENGLISH)
    assertTrue(templates.isNotEmpty())
    validateFixedQuestions(templates)
  }

  private fun validateFixedQuestions(templates: List<QuestionTemplateDto>) {
    for (i in 0..<templates.size) {
      val result = validateSpec(templates[i].responseFormat, templates[i].responseSpec, null)
      assertTrue(
        result.isValid,
        "Question id=${templates[i].id} failed validation: ${result.message}",
      )
    }
  }

  @Test
  fun `validate custom ENGLISH questions specs in the db`() {
    validateCustomQuestions(Language.ENGLISH)
  }

  @Test
  fun `validate custom WELSH questions specs in the db`() {
    validateCustomQuestions(Language.WELSH)
  }

  private fun validateCustomQuestions(language: Language) {
    val templates = questionRepository.getQuestionTemplates(language)
    for (i in 0..<templates.size) {
      val result = validateSpec(templates[i].responseFormat, templates[i].responseSpec, null)
      assertTrue(
        result.isValid,
        "Question id=${templates[i].id} failed validation: ${result.message}",
      )
    }
  }
}

val assignCustomQuestionsRequest = AssignCustomQuestionsRequest(
  listOf(CustomQuestionItem(1, mapOf("key" to "value"))),
  Language.ENGLISH,
  "KENNY.POWERS",
)
