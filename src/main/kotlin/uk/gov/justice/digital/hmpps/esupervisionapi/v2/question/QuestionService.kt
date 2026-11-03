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
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CustomQuestionItem
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Language
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderQuestion
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderQuestionList
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
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
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinMode
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.placeholders
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.collections.emptyMap

@Service
@Validated
class QuestionService(
  private val questionsRepository: QuestionRepository,
  private val questionListAssignmentRepository: QuestionListAssignmentRepository,
  private val offenderRepository: OffenderRepository,
  private val checkinService: CheckinService,
  private val checkinRepository: OffenderCheckinRepository,
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
  fun upcomingAssignment(offender: Offender): UpcomingQuestionAssignmentInfo {
    require(offender.status == OffenderStatus.VERIFIED) { "Offender status is ${offender.status}" }
    val today = clock.today()
    val next = when (offender.mode) {
      CheckinMode.SCHEDULED -> nextCheckinDay(offender, today, CheckinScheduleLowerBound.INCLUDE_TODAY)
      CheckinMode.AD_HOC -> offender.firstCheckin
    }
    val info = questionListAssignmentRepository.upcomingAssignmentAndDueDate(offender.id, today, next, checkinWindow.toDays())

    return UpcomingQuestionAssignmentInfo(
      info.dueDate,
      info.questionListId,
    )
  }

  /**
   * Assigns questions to an offender's upcoming check-in.
   *
   * Different behaviour depending on the offender's check-in mode: For ad-hoc check-ins, we can assign questions
   * if one is scheduled (but not **created** yet) for today.
   */
  @Transactional
  fun assignCustomQuestions(crn: CRN, @ValidQuestionParams request: AssignCustomQuestionsRequest): AssignCustomQuestionsResponse {
    require(crn.matches(crnRegex))
    validateQuestionRequest(request)

    val offender = offenderRepository.findByCrn(crn).orElseThrow { BadArgumentException("Offender not found for CRN=$crn") }
    if (offender.status != OffenderStatus.VERIFIED) {
      throw BadArgumentException("Can't add question to offender with status ${offender.status}")
    }
    val today = clock.today()
    val firstCheckin = offender.firstCheckin
    when (offender.mode) {
      CheckinMode.AD_HOC -> if (firstCheckin == null || firstCheckin < today) {
        throw BadArgumentException("offender does not have an upcoming check-in")
      }
      CheckinMode.SCHEDULED -> null
    }

    // an upcoming check-in isn't a check-in yet: until it is activated, treat today as if it didn't exist
    val checkins = checkinRepository.findAllByOffenderAndDueDate(offender, today)
      .filter { it.status != CheckinStatus.SCHEDULED }
    val isDueToday = when (offender.mode) {
      CheckinMode.SCHEDULED -> isCheckinDay(offender, today)
      CheckinMode.AD_HOC -> offender.firstCheckin == today
    }
    if ((checkins.isEmpty() && isDueToday && offender.mode == CheckinMode.SCHEDULED) || checkins.any { it.status == CheckinStatus.CREATED }) {
      throw BadArgumentException("Offender is due for a checkin. Too late to assign questions.")
    }

    val listId = createQuestionList(materialiseBespokeQuestions(request))

    val assigned = questionListAssignmentRepository.createAssignment(offender.id, listId) == 1
    LOG.info("Question list assignment for offender={}, listId={} assigned?={}", crn, listId, assigned)
    if (!assigned) {
      throw BadArgumentException("Too late to assign questions. Checkin possibly CREATED for offender=$crn: firstCheckin=${offender.firstCheckin}, interval=${offender.checkinInterval}")
    }

    val nextCheckin = when (offender.mode) {
      CheckinMode.SCHEDULED -> nextCheckinDay(offender, today)
      CheckinMode.AD_HOC -> offender.firstCheckin
    }
    return AssignCustomQuestionsResponse(
      requireNotNull(nextCheckin) {
        // we should not be able to get here and have a null nextCheckin
        "Can't assign questions when next checkin date is null. offender=$crn: firstCheckin=${offender.firstCheckin}, interval=${offender.checkinInterval}, mode=${offender.mode}"
      },
      listId,
    )
  }

  private fun createQuestionList(request: AssignCustomQuestionsRequest): Long = questionsRepository.upsertQuestionList(
    null,
    request.author,
    request.questions.map { item ->
      if (!item.params.containsKey("placeholders")) {
        throw BadArgumentException("Question ${item.id} is missing placeholders")
      }
      mapOf("id" to requireNotNull(item.id), "params" to item.params)
    },
  ) ?: throw RuntimeException("Failed to create question list.")

  /**
   * A bespoke question has no template to point at, so a question row is created for the author
   * (or an identical earlier one reused) before the list is built. The returned request references
   * every question by id.
   *
   * Distinct texts are defined in sorted order. The whole assignment is one transaction, and an insert
   * on uk_question_uuid waits for an uncommitted row with the same uuid, so two concurrent assignments
   * that introduced the same new texts in different orders could otherwise deadlock.
   */
  private fun materialiseBespokeQuestions(request: AssignCustomQuestionsRequest): AssignCustomQuestionsRequest {
    val idsByText = request.questions
      .filter { it.hasBespokeText() }
      .map { requireNotNull(it.text).trim() }
      .distinct()
      .sorted()
      .associateWith { questionsRepository.defineBespokeQuestion(request.author, it) }
    return request.copy(
      questions = request.questions.map { item ->
        if (item.hasBespokeText()) {
          val questionId = idsByText.getValue(requireNotNull(item.text).trim())
          CustomQuestionItem(id = questionId, params = mapOf("placeholders" to emptyMap<String, String>()))
        } else {
          item
        }
      },
    )
  }

  /**
   * Checks template questions against their templates. Bespoke questions have no template; the
   * request validator has already checked they carry text.
   *
   * Any CUSTOMISABLE question that has not been retired is accepted by id, whoever authored it. That
   * includes a bespoke question another practitioner wrote: a client reusing ids it was given from
   * an existing list must keep working when a colleague takes over, and the text is already visible
   * to practitioners. The UI re-sends earlier bespoke questions as text, so they are redefined for
   * the new author rather than shared.
   */
  private fun validateQuestionRequest(request: AssignCustomQuestionsRequest) {
    val templateQuestions = request.questions.filter { !it.hasBespokeText() }
    if (templateQuestions.isEmpty()) {
      return
    }
    val lookupsById = questionsRepository
      .getQuestionTemplates(templateQuestions.map { requireNotNull(it.id) }, request.language)
      .associateBy { it.template.id }
    templateQuestions.forEach {
      val lookup = lookupsById[it.id] ?: throw BadArgumentException("No question with ID=${it.id}")
      if (lookup.template.policy != QuestionPolicy.CUSTOMISABLE) {
        throw BadArgumentException("Question ${it.id} is not customisable")
      }
      // Retired templates stay resolvable for lists assigned before retirement but are no longer offered
      if (lookup.retired) {
        throw BadArgumentException("Question ${it.id} has been retired")
      }
      validateAgainstTemplates(it, lookup.template)
    }
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
    if (checkin.status != CheckinStatus.CREATED) {
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
