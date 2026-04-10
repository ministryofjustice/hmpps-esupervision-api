package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.mockito.kotlin.any
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.MutableTestClock
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.TestClockConfiguration
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AssignCustomQuestionsRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CreateCheckinByCrnV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CustomQuestionItem
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Language
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignmentRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionTemplateDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SubmitCheckinV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.placeholders
import java.time.Clock
import java.time.Duration
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfiguration::class)
class QuestionsIT : IntegrationTestBase() {

  @Autowired lateinit var questionRepository: QuestionRepository

  @Autowired lateinit var questionListItemRepository: DebugQuestionsRepository

  @Autowired lateinit var questionListAssignmentRepository: QuestionListAssignmentRepository

  @Autowired lateinit var questionDefinitionRepository: QuestionDefinitionRepository

  @Autowired lateinit var questionService: QuestionService

  @Autowired lateinit var offenderV2Repository: OffenderV2Repository

  @Autowired lateinit var offenderCheckinV2Repository: OffenderCheckinV2Repository

  @Autowired lateinit var offenderCheckinService: CheckinV2Service

  @Autowired lateinit var clock: Clock

  @MockitoBean lateinit var s3UploadService: S3UploadService

  @BeforeEach
  fun setUp() {
    (clock as MutableTestClock).advanceTo(Instant.now())

    reset(s3UploadService)
    whenever(s3UploadService.isCheckinVideoUploaded(any())).thenReturn(true)

    questionDefinitionRepository.defineCustomQuestion(
      "BARRY.WHITE",
      "Did you finish {{thing}}",
      """
        {
          "placeholders": ["thing"],
          "hint": "Hint for the {{thing}} question",
          "domain_msg_head": "What did they say about the {{thing}}?"
        }
      """.trimIndent(),
    )
  }

  @AfterEach
  fun tearDown() {
    questionListItemRepository.deleteAllNonSystem()
    questionListAssignmentRepository.deleteAll()
    questionListItemRepository.deleteCustomQuestions()
    offenderCheckinV2Repository.deleteAll()
    offenderV2Repository.deleteAll()
  }

  @Test
  fun `define a list of custom questions - success`() {
    val defaultQuestions = questionRepository.getListItems(1)
    assertEquals(2, defaultQuestions.size)

    // get list of custom questions and add a list
    val author = "BARRY.WHITE"
    val customQuestions = questionRepository.getQuestionTemplates(Language.ENGLISH, author)
    val upsertedList = questionRepository.upsertQuestionList(
      null,
      author,
      customQuestions.mapIndexed { idx, it ->

        mapOf(
          "id" to it.id as Any,
          "params" to mapOf("placeholders" to mapOf("thing" to "School ${idx + 1}")),
        )
      },
    )

    val allListItems = questionListItemRepository.findAllItems()
    assertEquals(defaultQuestions.size + 1, allListItems.size)

    assertTrue(upsertedList != null)
    val upsertedItems = questionRepository.getListItems(upsertedList!!)
    assertEquals(2 + 1, upsertedItems.size)
  }

  @Test
  fun `QuestionService - assign custom questions - success`() {
    val offender = offenderTemplate.copy(crn = "A123456").toEntity()
    offenderV2Repository.save(offender)

    val systemTemplates = questionService.listQuestionTemplates(Language.ENGLISH)
    assertEquals(0, systemTemplates.size)

    val templates = questionRepository.getQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    assertEquals(1, templates.size)

    val addQuestionsRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    val resp = questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)

    val qlitems = questionListItemRepository.findAllItems()
    assertEquals(2 + 1, qlitems.size)

    val offenderQuestions = questionService.offenderQuestionList(resp.listId, Language.ENGLISH)
    assertEquals(2 + 1, offenderQuestions.questions.size)
  }

  @Test
  fun `Checkin status change causes assignment update`() {
    val offender = offenderTemplate.copy(crn = "A123456").toEntity()
    offenderV2Repository.save(offender)

    val systemTemplates = questionService.listQuestionTemplates(Language.ENGLISH)
    assertEquals(0, systemTemplates.size)

    val templates = questionRepository.getQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    assertEquals(1, templates.size)

    val addQuestionsRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    val resp = questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)

    val qlitems = questionListItemRepository.findAllItems()
    assertEquals(2 + 1, qlitems.size)

    val checkin = offenderCheckinService.createCheckinByCrn(CreateCheckinByCrnV2Request("BARRY.WHITE", offender.crn, clock.today()))
    val assignment = questionService.upcomingAssignment(offender.crn)
    assertNotNull(assignment.questionList)

    clock.advanceBy(Duration.ofHours(4))
    offenderCheckinService.submitCheckin(checkin.uuid, SubmitCheckinV2Request(mapOf("version" to "whatever")))

    val assignmentAfterSubmission = questionService.upcomingAssignment(offender.crn)
    assertNull(assignmentAfterSubmission.questionList)
  }

  @Test
  fun `QuestionService - assign custom questions - failure (it's checkin day)`() {
    val offender = offenderTemplate.copy(crn = "A000002", firstCheckin = clock.today()).toEntity()
    offenderV2Repository.save(offender)
    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val addQuestionsRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)

    // it's checkin day, so it should fail
    assertThrows(BadArgumentException::class.java) {
      questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)
    }

    clock.advanceBy(Duration.ofDays(1))

    val moreThan3Questions = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates + templates + templates + templates)
    assertThrows(jakarta.validation.ConstraintViolationException::class.java) {
      questionService.assignCustomQuestions(offender.crn, moreThan3Questions)
    }
  }

  @Test
  fun `QuestionService - assign custom questions when prev checkin had custom qs - success`() {
    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val addQuestionsRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)

    // ----- DAY 1
    val offender = offenderTemplate.copy(crn = "A000003", firstCheckin = clock.today()).toEntity()
    offenderV2Repository.save(offender)

    // can't assign custom questions the same day a checkin is due
    assertThrows(BadArgumentException::class.java) {
      questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)
    }

    val checkin1 = offenderCheckinService.createCheckinByCrn(
      CreateCheckinByCrnV2Request(
        "BARRY.WHITE",
        offender.crn,
        clock.today(),
      ),
    )
    clock.advanceBy(Duration.ofHours(1))
    val submission1 = offenderCheckinService.submitCheckin(checkin1.uuid, SubmitCheckinV2Request(mapOf("version" to "whatever")))

    questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)
    assertNotNull(questionListAssignmentRepository.upcomingAssignment(offender.id))

    // ----- DAY 2
    clock.advanceBy(Duration.ofDays(1))
    val checkin2 = offenderCheckinService.createCheckinByCrn(
      CreateCheckinByCrnV2Request(
        "BARRY.WHITE",
        offender.crn,
        clock.today(),
      ),
    )
    // there's a checkin with STATUS=CREATED already
    assertThrows(BadArgumentException::class.java) {
      questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)
    }
    val submission2 = offenderCheckinService.submitCheckin(checkin2.uuid, SubmitCheckinV2Request(mapOf("version" to "whatever")))

    // the assignment should have the checkin set
    val assignment = questionListAssignmentRepository.upcomingAssignment(offender.id)
    assertNull(assignment, "No assignment should be present after checkin1 was submitted: $assignment")
  }
}

private fun Clock.advanceBy(duration: Duration) = (this as MutableTestClock).advanceBy(duration)
private fun Clock.advanceTo(instant: Instant) = (this as MutableTestClock).advanceTo(instant)

private fun makeAssignCustomQuestionsRequest(
  language: Language,
  templates: List<QuestionTemplateDto>,
) = AssignCustomQuestionsRequest(
  author = "BARRY.WHITE",
  language = language,
  questions = templates.mapIndexed { index, dto ->
    val paramsPlaceholders = mutableMapOf<String, Any>()
    dto.placeholders().forEach { paramsPlaceholders[it] = "$it value $index" }
    CustomQuestionItem(id = dto.id, params = mapOf("placeholders" to paramsPlaceholders))
  },
)
