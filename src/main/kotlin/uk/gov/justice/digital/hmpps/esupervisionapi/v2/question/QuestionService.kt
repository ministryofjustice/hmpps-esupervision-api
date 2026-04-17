package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AssignCustomQuestionsRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AssignCustomQuestionsResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Language
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderQuestion
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderQuestionList
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignmentRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListItemDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionPolicy
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionTemplateDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.UpcomingQuestionAssignmentInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.UpcomingQuestionListItems
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.isCheckinDay
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.nextCheckinDay
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.placeholders
import java.time.Clock
import kotlin.collections.emptyMap

@Service
@Validated
class QuestionService(
  private val questionsRepository: QuestionRepository,
  private val questionListAssignmentRepository: QuestionListAssignmentRepository,
  private val offenderRepository: OffenderV2Repository,
  private val checkinService: CheckinV2Service,
  private val checkinRepository: OffenderCheckinV2Repository,
  private val clock: Clock,
) {

  private val crnRegex = Regex("[A-Z]\\d{6}")

  @Transactional(readOnly = true)
  fun listQuestionTemplates(language: Language, author: ExternalUserId = "SYSTEM"): List<QuestionTemplateDto> = questionsRepository.getQuestionTemplates(language, author)

  @Transactional(readOnly = true)
  fun offenderQuestionList(listId: Long, language: Language): OffenderQuestionList {
    require(listId > 0)

    val questions = questionsRepository.getListItems(listId).map { it.evalTemplate() }
    return OffenderQuestionList(listId, questions)
  }

  /**
   * Assuming the CRN is VERIFIED, always returns list items (from default or explicitly assigned list)
   */
  @Transactional(readOnly = true)
  fun upcomingQuestionListItems(crn: CRN, language: Language): UpcomingQuestionListItems {
    require(crn.matches(crnRegex))
    val offender = offenderRepository.findByCrn(crn).orElseThrow { BadArgumentException("Offender not found for CRN=$crn") }
    if (offender.status != OffenderStatus.VERIFIED) {
      throw BadArgumentException("Offender status is ${offender.status}")
    }

    val upcoming = upcomingAssignment(crn)
    val items = if (upcoming.questionList != null) questionsRepository.getListItems(upcoming.questionList, language) else questionsRepository.defaultListItems(language)
    return UpcomingQuestionListItems(upcoming.expectedCheckinDate, items)
  }

  /**
   * Returns a minimal set of information about the upcoming question list assignment.
   */
  @Transactional(readOnly = true)
  fun upcomingAssignment(crn: CRN): UpcomingQuestionAssignmentInfo {
    require(crn.matches(crnRegex))
    val offender = offenderRepository.findByCrn(crn).orElseThrow { BadArgumentException("Offender not found for CRN=$crn") }
    if (offender.status != OffenderStatus.VERIFIED) {
      throw BadArgumentException("Offender status is ${offender.status}")
    }

    val listId = questionListAssignmentRepository.upcomingAssignment(offender.id)
    val today = clock.today()
    return UpcomingQuestionAssignmentInfo(
      if (isCheckinDay(offender, today)) today else nextCheckinDay(offender, today),
      listId,
    )
  }

  @Transactional
  fun assignCustomQuestions(crn: CRN, @ValidQuestionParams request: AssignCustomQuestionsRequest): AssignCustomQuestionsResponse {
    require(crn.matches(crnRegex))
    // validate the supplied params
    val questionsById = questionsRepository
      .getQuestionTemplates(request.questions.map { it.id }, request.language)
      .associateBy { it.id }
    request.questions.forEach {
      val template = questionsById[it.id] ?: throw BadArgumentException("No question with ID=${it.id}")
      require(template.policy == QuestionPolicy.CUSTOMISABLE) { "Question ${it.id} is not customisable" }
      validateAgainstTemplates(it, template)
    }

    val offender = offenderRepository.findByCrn(crn).orElseThrow { BadArgumentException("Offender not found for CRN=$crn") }
    if (offender.status != OffenderStatus.VERIFIED) {
      throw BadArgumentException("Can't add question to offender with status ${offender.status}")
    }
    val maybeCheckin = checkinRepository.findByOffenderAndDueDate(offender, clock.today())
    if (maybeCheckin.isEmpty && isCheckinDay(offender, clock.today())) {
      throw BadArgumentException("Offender is due for a checkin. Too late to assign questions.")
    } else {
      maybeCheckin.ifPresent {
        if (it.status == CheckinV2Status.CREATED) {
          throw BadArgumentException("Checkin already due and CREATED. Too late to assign questions.")
        }
      }
    }

    val listId = questionsRepository.upsertQuestionList(
      null,
      request.author,
      request.questions.mapIndexed { index, item ->
        assert(item.params.containsKey("placeholders"))
        mapOf("id" to item.id, "params" to item.params)
      },
    )
    if (listId == null) {
      LOG.warn("Failed to create question list for CRN={}, author={}, questions={}", crn, request.author, request.questions)
      throw RuntimeException("Failed to create question list.")
    }

    val assigned = questionListAssignmentRepository.createAssignment(offender.id, listId) == 1
    LOG.info("Question list assignment for offender={}, listId={} assigned?={}", crn, listId, assigned)
    if (!assigned) {
      throw BadArgumentException("Too late to assign questions. Checkin possibly CREATED for offender=$crn: firstCheckin=${offender.firstCheckin}, interval=${offender.checkinInterval}")
    }

    return AssignCustomQuestionsResponse(nextCheckinDay(offender, clock.today()), listId)
  }

  @Transactional
  fun deleteUpcomingAssignment(crn: CRN): Boolean {
    require(crn.matches(crnRegex))
    val offender = offenderRepository.findByCrn(crn).orElseThrow { BadArgumentException("Offender not found for CRN=$crn") }

    val result = questionListAssignmentRepository.deleteUpcomingAssignment(offender.id)
    LOG.info("Removed upcoming question list assignment for CRN={}, result={}", crn, result)
    return result == 1
  }

  companion object {
    val LOG = logger<QuestionService>()
  }
}

fun QuestionListItemDto.evalTemplate(): OffenderQuestion {
  val spec = this.template.responseSpec
  var templateString = this.template.template

  var hint = spec["hint"] as String
  val values = (this.params["placeholders"] ?: emptyMap<String, String>()) as Map<String, String>
  for (placeholder in this.template.placeholders()) {
    val value = values[placeholder]?.trim()
    templateString = templateString.replacePlaceholder(placeholder, value)
    hint = hint.replacePlaceholder(placeholder, value)
  }
  val processedSpec = spec.toMutableMap()
  processedSpec["hint"] = hint

  assert(templateString.isNotBlank())
  // assert(!templateString.contains("{{") && !templateString.contains("}}"))

  return OffenderQuestion(templateString, this.template.responseFormat, processedSpec)
}

private fun String.replacePlaceholder(placeholder: String, value: String?): String = this.replace("{{$placeholder}}", value ?: "{{$placeholder}}")
