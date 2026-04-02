package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AssignCustomQuestionsRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CustomQuestionItem
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Language

class ValidatorTest : IntegrationTestBase() {
  @Autowired lateinit var validator: Validator

  @Autowired lateinit var questionListItemsRepository: QuestionListItemsRepository

  @Test
  fun `AssignCustomQuestionsRequest validator - fail, no responseFormat in params`() {
    val result = validator.validate(assignCustomQuestionsRequest)
    assertTrue(result.isNotEmpty())
  }

  @Test
  fun `AssignCustomQuestionsRequest validator - pass`() {
    val req = assignCustomQuestionsRequest.copy(
      questions = listOf(CustomQuestionItem(1, mapOf("responseFormat" to "TEXT", "key" to "value"))),
    )
    val result = validator.validate(req)
    assertTrue(result.isEmpty())
  }

  @Test
  fun `AssignCustomQuestionsRequest validator - fail, invalid placeholders`() {
    val req = assignCustomQuestionsRequest.copy(
      questions = listOf(CustomQuestionItem(1, mapOf("responseFormat" to "TEXT", "placeholders" to "invalid"))),
    )
    val result = validator.validate(req)
    assertTrue(result.isNotEmpty())
  }

  @Test
  fun `AssignCustomQuestionsRequest validator - fail, placeholders are not strings`() {
    val req = assignCustomQuestionsRequest.copy(
      questions = listOf(CustomQuestionItem(1, mapOf("responseFormat" to "TEXT", "placeholders" to listOf(1, 2, 3)))),
    )
    val result = validator.validate(req)
    assertTrue(result.isNotEmpty())
  }

  @Test
  fun `AssignCustomQuestionsRequest validator - pass, valid placeholders`() {
    val req = assignCustomQuestionsRequest.copy(
      questions = listOf(CustomQuestionItem(1, mapOf("responseFormat" to "TEXT", "placeholders" to mapOf("a" to "b")))),
    )
    val result = validator.validate(req)
    assertTrue(result.isEmpty())
  }

  @Test
  fun `validate default questions have been configured properly`() {
    val items = questionListItemsRepository.findAllItems()
    for (i in 0..<items.size) {
      val item = items[i]
      val params = item.params
      assertTrue(validateParams(CustomQuestionItem(1, params), i, null), "Question $i failed validation")
    }
  }
}

val assignCustomQuestionsRequest = AssignCustomQuestionsRequest(
  listOf(CustomQuestionItem(1, mapOf("key" to "value"))),
  Language.ENGLISH,
  "KENNY.POWERS",
)
