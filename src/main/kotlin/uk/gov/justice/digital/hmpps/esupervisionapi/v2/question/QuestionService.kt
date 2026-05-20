package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import org.springframework.beans.factory.annotation.Value
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
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignmentRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListItemDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionPolicy
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionTemplateDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.UpcomingQuestionAssignmentInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.UpcomingQuestionListItems
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinScheduleLowerBound
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.isCheckinDay
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.nextCheckinDay
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.placeholders
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.collections.emptyMap
import kotlin.jvm.optionals.getOrNull

@Service
@Validated
class QuestionService(
  private val questionsRepository: QuestionRepository,
  private val questionListAssignmentRepository: QuestionListAssignmentRepository,
  private val offenderRepository: OffenderV2Repository,
  private val checkinService: CheckinV2Service,
  private val checkinRepository: OffenderCheckinV2Repository,
  private val clock: Clock,
  @param:Value("\${app.scheduling.checkin-notification.window:72h}") private val checkinWindow: Duration,
) {

  private val crnRegex = Regex("[A-Z]\\d{6}")

  @Transactional(readOnly = true)
  fun listQuestionTemplates(language: Language, author: ExternalUserId = "SYSTEM"): List<QuestionTemplateDto> = questionsRepository.getQuestionTemplates(language, author)

  @Transactional(readOnly = true)
  fun offenderQuestionList(listId: Long, language: Language): OffenderQuestionList {
    require(listId > 0)

    val questions = questionsRepository.getListItems(listId, language).map { it.evalTemplate() }
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

    val upcoming = upcomingAssignment(offender)
    val items = if (upcoming.questionList != null) questionsRepository.getListItems(upcoming.questionList, language) else questionsRepository.defaultListItems(language)
    return UpcomingQuestionListItems(upcoming.expectedCheckinDate, items)
  }

  /**
   * Returns a minimal set of information about the upcoming question list assignment.
   */
  @Transactional(readOnly = true)
  fun upcomingAssignment(offender: OffenderV2): UpcomingQuestionAssignmentInfo {
    require(offender.status == OffenderStatus.VERIFIED) { "Offender status is ${offender.status}" }
    val today = clock.today()
    val next = nextCheckinDay(offender, today, CheckinScheduleLowerBound.INCLUDE_TODAY)
    val info = questionListAssignmentRepository.upcomingAssignmentAndDueDate(offender.id, next, checkinWindow.toDays())

    return UpcomingQuestionAssignmentInfo(
      info.dueDate,
      info.questionListId,
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
    val today = clock.today()
    val checkin = checkinRepository.findByOffenderAndDueDate(offender, today).getOrNull()
    if ((checkin == null && isCheckinDay(offender, today)) || (checkin != null && checkin.status == CheckinV2Status.CREATED)) {
      throw BadArgumentException("Offender is due for a checkin. Too late to assign questions.")
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

    return AssignCustomQuestionsResponse(nextCheckinDay(offender, today), listId)
  }

  @Transactional
  fun deleteUpcomingAssignment(crn: CRN): Boolean {
    require(crn.matches(crnRegex))
    val offender = offenderRepository.findByCrn(crn).orElseThrow { BadArgumentException("Offender not found for CRN=$crn") }

    val result = questionListAssignmentRepository.deleteUpcomingAssignment(offender.id)
    LOG.info("Removed upcoming question list assignment for CRN={}, result={}", crn, result)
    return result == 1
  }

  @Transactional(readOnly = true)
  fun checkinQuestions(checkinUuid: UUID, language: Language): List<QuestionListItemDto> {
    val checkin = checkinRepository.findByUuid(checkinUuid).orElseThrow { BadArgumentException("Checkin not found for UUID=$checkinUuid") }
    if (checkin.status != CheckinV2Status.CREATED) {
      throw BadArgumentException("Can't checkin questions for checkin with status ${checkin.status}")
    }
    val listId = questionListAssignmentRepository.checkinAssignment(checkin.id)
    val items = if (listId != null) questionsRepository.getListItems(listId, language) else questionsRepository.defaultListItems(language)
    LOG.info("checkinQuestions: returning {} items for checkin UUID={}, listId={}", items.size, checkinUuid, listId)
    return items
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

fun String.replacePlaceholder(placeholder: String, value: String?): String = this.replace("{{$placeholder}}", value ?: "{{$placeholder}}")
