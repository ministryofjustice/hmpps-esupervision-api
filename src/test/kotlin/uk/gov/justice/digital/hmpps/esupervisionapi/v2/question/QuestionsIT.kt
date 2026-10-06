package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.mockito.kotlin.any
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.GeneratingStubDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.MutableTestClock
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.TestClockConfiguration
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AssignCustomQuestionsRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinCreatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinLogsDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinLogsHint
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CreateCheckinByCrnRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CustomQuestionItem
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Language
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PartialCheckinCreatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignmentRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionTemplateDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SubmitCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinScheduleLowerBound
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.nextCheckinDay
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinMode
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.CheckinScheduleWithQuestionsRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDetailsUpdateRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.placeholders
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfiguration::class)
class QuestionsIT(
  @param:Value("\${app.scheduling.checkin-notification.window:72h}") private val checkinWindow: Duration,
) : IntegrationTestBase() {

  @Autowired
  lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  lateinit var questionRepository: QuestionRepository

  @Autowired
  lateinit var questionListItemRepository: DebugQuestionsRepository

  @Autowired
  lateinit var questionListAssignmentRepository: QuestionListAssignmentRepository

  @Autowired
  lateinit var questionDefinitionRepository: QuestionDefinitionRepository

  @Autowired
  lateinit var questionService: QuestionService

  @Autowired
  lateinit var offenderRepository: OffenderRepository

  @Autowired
  lateinit var offenderCheckinRepository: OffenderCheckinRepository

  @Autowired
  lateinit var offenderEventLogRepository: OffenderEventLogRepository

  @Autowired
  lateinit var offenderSetupRepository: OffenderSetupRepository

  @Autowired
  lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired
  lateinit var genericNotificationRepository: GenericNotificationRepository

  @Autowired
  lateinit var offenderCheckinService: CheckinService

  @Autowired
  lateinit var checkinCreationService: CheckinCreationService

  @Autowired
  lateinit var clock: Clock

  @MockitoBean
  lateinit var s3UploadService: S3UploadService

  @MockitoBean
  lateinit var ndiliusApiClient: INdiliusApiClient

  @MockitoBean lateinit var notificationService: NotificationService

  @BeforeEach
  fun setUp() {
    (clock as MutableTestClock).advanceTo(Instant.now())

    reset(s3UploadService, ndiliusApiClient, notificationService)
    whenever(s3UploadService.isCheckinVideoUploaded(any())).thenReturn(true)
    whenever(ndiliusApiClient.getContactDetails(any())).thenAnswer { invocation ->
      GeneratingStubDataProvider().provideCase(invocation.getArgument<String>(0))
    }

    questionDefinitionRepository.defineCustomQuestion(
      "BARRY.WHITE",
      "Did you finish {{thing}}",
      """
        {
          "placeholders": ["thing"],
          "hint": "Hint for the {{thing}} question",
          "domain_msg_head": "What did they say about the {{thing}}?",
          "placeholders_examples": [ {"thing": "School"}, {"thing": "Work"} ] 
        }
      """.trimIndent(),
    )
  }

  @AfterEach
  fun tearDown() {
    questionListItemRepository.deleteAllNonSystem()
    questionListAssignmentRepository.deleteAll()
    questionListItemRepository.deleteCustomQuestions()

    jdbcTemplate.update("TRUNCATE TABLE generic_notification_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_checkin_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE event_audit_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE outbox_items RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_v2 RESTART IDENTITY CASCADE")
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
    offenderRepository.save(offender)

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
    offenderRepository.save(offender)
    val defaultListId = questionListItemRepository.findDefaultListId()
    assertNotNull(defaultListId)

    val templates = questionRepository.getQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    assertEquals(1, templates.size)

    val addQuestionsRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)

    val qlitems = questionListItemRepository.findAllItems()
    assertTrue(qlitems.size > 1)

    val checkin =
      offenderCheckinService.createCheckinByCrn(CreateCheckinByCrnRequest("BARRY.WHITE", offender.crn, clock.today()))
    val assignment = questionService.upcomingAssignment(offender)
    assertNotEquals(
      defaultListId,
      assignment.questionList,
      "Upcoming assignment should not flip to default list immediately after a checkin is created",
    )

    val checkinEntity = offenderCheckinRepository.findByUuid(checkin.uuid)
    val checkinQuestions = questionListAssignmentRepository.checkinAssignment(checkinEntity.get().id)
    assertNotNull(checkinQuestions, "The assignment should still be visible for the checkin UI")

    val checkinQuestionResponse = questionService.checkinQuestions(checkin.uuid, Language.ENGLISH)
    assertEquals(questionRepository.defaultListItems(Language.ENGLISH).size + 1, checkinQuestionResponse.size)

    clock.advanceBy(Duration.ofHours(4))
    offenderCheckinService.submitCheckin(checkin.uuid, SubmitCheckinRequest(mapOf("version" to "whatever")))

    val assignmentAfterSubmission = questionService.upcomingAssignment(offender)
    assertEquals(
      defaultListId,
      assignmentAfterSubmission.questionList,
      "Upcoming assignment should flip to default list after a checkin is submitted",
    )
  }

  @Test
  fun `QuestionService - assign custom questions - failure (it's checkin day)`() {
    val offender = offenderTemplate.copy(crn = "A000002", firstCheckin = clock.today()).toEntity()
    offenderRepository.save(offender)
    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val addQuestionsRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)

    // it's checkin day, so it should fail
    assertThrows(BadArgumentException::class.java) {
      questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)
    }

    clock.advanceBy(Duration.ofDays(1))

    val moreThan3Questions =
      makeAssignCustomQuestionsRequest(Language.ENGLISH, templates + templates + templates + templates)
    assertThrows(jakarta.validation.ConstraintViolationException::class.java) {
      questionService.assignCustomQuestions(offender.crn, moreThan3Questions)
    }
  }

  @Test
  fun `QuestionService - submitted checkin on due date, correct expected check in date returned`() {
    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val dueDate = clock.today().plusDays(1)
    val offender = offenderTemplate.copy(crn = "A000003", firstCheckin = dueDate).toEntity()
    offenderRepository.save(offender)

    // Day before due date
    val addQuestionsRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)

    clock.advanceBy(Duration.ofDays(1))

    // Day = due date
    val checkin = offenderCheckinService.debugCreateCheckin(offender, clock)
    val upcomingListWithCheckin = questionService.upcomingQuestionListItems(offender.crn, Language.ENGLISH)
    assertEquals(dueDate, upcomingListWithCheckin.expectedCheckinDate)

    offenderCheckinService.submitCheckin(checkin.uuid, SubmitCheckinRequest(mapOf("version" to "whatever")))

    val upcomingAfterSubmission = questionService.upcomingQuestionListItems(offender.crn, Language.ENGLISH)
    assertEquals(dueDate.plusDays(offender.checkinInterval!!.toDays()), upcomingAfterSubmission.expectedCheckinDate)

    val info = questionListAssignmentRepository.upcomingAssignmentAndDueDate(
      offender.id,
      clock.today(),
      dueDate,
      checkinWindow.toDays(),
    )
    // assertNull(info.dueDate)
    assertEquals(dueDate.plusDays(offender.checkinInterval!!.toDays()), info.dueDate)

    clock.advanceBy(Duration.ofDays(1))

    // Day = due date + 1 day
    val upcomingDayAfterDueDate = questionService.upcomingQuestionListItems(offender.crn, Language.ENGLISH)
    assertEquals(dueDate.plusDays(offender.checkinInterval!!.toDays()), upcomingDayAfterDueDate.expectedCheckinDate)
  }

  @Test
  fun `QuestionService - assigning and fetching questions for ad-hoc check-ins`() {
    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val dueDate = clock.today().plusDays(-1)
    val offender =
      offenderTemplate.copy(crn = "A000003", mode = CheckinMode.AD_HOC, checkinInterval = null, firstCheckin = dueDate)
        .toEntity()
    offenderRepository.save(offender)

    val assignment = questionService.upcomingAssignment(offender)
    assertNull(assignment.expectedCheckinDate, "No check-in scheduled, there should be no expected checkin date")

    // no check-in scheduled => can't assign questions
    val addQuestionsRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    assertThrows(BadArgumentException::class.java) {
      questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)
    }

    offender.firstCheckin = clock.today().plusDays(4)
    offenderRepository.save(offender)

    // there's an upcoming check-in => can assign questions
    questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)
    val assignmentAfter = questionService.upcomingAssignment(offender)
    assertEquals(offender.firstCheckin, assignmentAfter.expectedCheckinDate)
  }

  @Test
  fun `schedule ad-hoc checkin for today with questions and reject later changes`() {
    val offender = offenderTemplate.copy(
      crn = "A123457",
      mode = CheckinMode.AD_HOC,
      checkinInterval = null,
      firstCheckin = clock.today().minusDays(1),
    ).toEntity()
    offenderRepository.save(offender)

    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val initialRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    val notificationProcessed = CountDownLatch(1)
    val questionsVisibleWhenNotificationRuns = AtomicBoolean(false)
    whenever(notificationService.sendCheckinCreatedNotifications(any())).thenAnswer { invocation ->
      val event = invocation.getArgument<CheckinCreatedEvent>(0)
      val notifiedCheckin = offenderCheckinRepository.findByUuid(event.checkin.uuid).orElseThrow()
      val assignedListId = questionListAssignmentRepository.checkinAssignment(notifiedCheckin.id)
      questionsVisibleWhenNotificationRuns.set(
        assignedListId != null && questionRepository.getListItems(assignedListId, Language.ENGLISH).any { it.params.isNotEmpty() },
      )
      notificationProcessed.countDown()
      null
    }
    val todaySchedule = CheckinScheduleWithQuestionsRequest(
      requestedBy = "BARRY.WHITE",
      firstCheckin = clock.today(),
      checkinInterval = null,
      mode = CheckinMode.AD_HOC,
      questions = initialRequest,
    )

    webTestClient.post()
      .uri("/v2/offenders/${offender.uuid}/update_details")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
      .bodyValue(OffenderDetailsUpdateRequest(checkinSchedule = todaySchedule))
      .exchange()
      .expectStatus().isOk

    assertTrue(notificationProcessed.await(5, TimeUnit.SECONDS), "check-in notification was not processed")
    assertTrue(questionsVisibleWhenNotificationRuns.get(), "questions must be assigned before notification processing")

    val checkin = offenderCheckinRepository.findByOffenderAndDueDate(offender, clock.today()).orElseThrow()
    val firstListId = questionListAssignmentRepository.checkinAssignment(checkin.id)
    assertNotNull(firstListId)
    assertEquals(
      initialRequest.questions.map { it.params },
      questionRepository.getListItems(firstListId!!, Language.ENGLISH).filter { it.params.isNotEmpty() }.map { it.params },
    )

    val replacementRequest = initialRequest.copy(
      questions = initialRequest.questions.map { item ->
        item.copy(params = mapOf("placeholders" to mapOf("thing" to "replacement value")))
      },
    )
    webTestClient.post()
      .uri("/v2/offenders/${offender.uuid}/update_details")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
      .bodyValue(
        OffenderDetailsUpdateRequest(
          checkinSchedule = todaySchedule.copy(questions = replacementRequest),
        ),
      )
      .exchange()
      .expectStatus().isEqualTo(422)

    val unchangedListId = questionListAssignmentRepository.checkinAssignment(checkin.id)
    assertEquals(firstListId, unchangedListId)
    assertEquals(
      initialRequest.questions.map { it.params },
      questionRepository.getListItems(unchangedListId!!, Language.ENGLISH).filter { it.params.isNotEmpty() }.map { it.params },
    )
    assertEquals(1, questionListAssignmentRepository.findAll().count { it.checkinId == checkin.id })
    assertEquals(checkin.uuid, offenderCheckinRepository.findByOffenderAndDueDate(offender, clock.today()).orElseThrow().uuid)
  }

  @Test
  fun `move pending ad-hoc checkin to today without resending questions`() {
    val offender = offenderTemplate.copy(
      crn = "A123461",
      mode = CheckinMode.AD_HOC,
      checkinInterval = null,
      firstCheckin = clock.today().plusDays(1),
    ).toEntity()
    offenderRepository.save(offender)
    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val questionRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    val pendingAssignment = questionService.assignCustomQuestions(offender.crn, questionRequest)
    val scheduleUpdate = CheckinScheduleWithQuestionsRequest(
      requestedBy = "BARRY.WHITE",
      firstCheckin = clock.today(),
      checkinInterval = null,
      mode = CheckinMode.AD_HOC,
    )

    webTestClient.post()
      .uri("/v2/offenders/${offender.uuid}/update_details")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
      .bodyValue(OffenderDetailsUpdateRequest(checkinSchedule = scheduleUpdate))
      .exchange()
      .expectStatus().isOk

    val checkin = offenderCheckinRepository.findByOffenderAndDueDate(offender, clock.today()).orElseThrow()
    val linkedAssignmentId = questionListAssignmentRepository.checkinAssignment(checkin.id)
    assertEquals(pendingAssignment.listId, linkedAssignmentId)
    assertEquals(1, questionListAssignmentRepository.findAll().count { it.checkinId == checkin.id })
    assertEquals(
      questionRequest.questions.map { it.params },
      questionRepository.getListItems(linkedAssignmentId!!, Language.ENGLISH).filter { it.params.isNotEmpty() }.map { it.params },
    )
  }

  @Test
  fun `scheduled job skips a stale candidate when a created checkin already exists`() {
    val offender = offenderTemplate.copy(
      crn = "A123464",
      mode = CheckinMode.AD_HOC,
      checkinInterval = null,
      firstCheckin = clock.today(),
    ).toEntity()
    offenderRepository.save(offender)
    val existingCheckin = offenderCheckinService.debugCreateCheckin(offender, clock)
    val staleCandidate = OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = offenderRepository.getReferenceById(offender.id),
      status = CheckinStatus.CREATED,
      dueDate = clock.today(),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
    )
    val contactDetails = GeneratingStubDataProvider().provideCase(offender.crn)
    val staleEvent = PartialCheckinCreatedEvent(
      offenderId = offender.id,
      practitionerId = offender.practitionerId,
      checkin = CheckinDto(
        uuid = staleCandidate.uuid,
        crn = offender.crn,
        status = staleCandidate.status,
        dueDate = staleCandidate.dueDate,
        createdAt = staleCandidate.createdAt,
        createdBy = staleCandidate.createdBy,
        personalDetails = contactDetails,
        checkinLogs = CheckinLogsDto(CheckinLogsHint.OMITTED, emptyList()),
      ),
      offenderContactPreference = offender.contactPreference,
      currentEvent = offender.currentEvent,
      checkinMode = offender.mode,
    )

    val created = checkinCreationService.createCheckins(listOf(staleCandidate to staleEvent))

    assertTrue(created.isEmpty())
    assertEquals(existingCheckin.uuid, offenderCheckinRepository.findAllByOffenderAndStatus(offender, CheckinStatus.CREATED).single().uuid)
  }

  @Test
  fun `scheduling today creates a fresh checkin when today's checkin was cancelled`() {
    val offender = offenderTemplate.copy(
      crn = "A123462",
      mode = CheckinMode.AD_HOC,
      checkinInterval = null,
      firstCheckin = clock.today().plusDays(1),
    ).toEntity()
    offenderRepository.save(offender)

    val cancelledDto = offenderCheckinService.debugCreateCheckin(offender, clock)
    val cancelledCheckin = offenderCheckinRepository.findByUuid(cancelledDto.uuid).orElseThrow()
    cancelledCheckin.status = CheckinStatus.CANCELLED
    offenderCheckinRepository.saveAndFlush(cancelledCheckin)

    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val questionRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    val pendingAssignment = questionService.assignCustomQuestions(offender.crn, questionRequest)
    val scheduleUpdate = CheckinScheduleWithQuestionsRequest(
      requestedBy = "BARRY.WHITE",
      firstCheckin = clock.today(),
      checkinInterval = null,
      mode = CheckinMode.AD_HOC,
    )

    webTestClient.post()
      .uri("/v2/offenders/${offender.uuid}/update_details")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
      .bodyValue(OffenderDetailsUpdateRequest(checkinSchedule = scheduleUpdate))
      .exchange()
      .expectStatus().isOk

    val freshCheckin = offenderCheckinRepository.findAllByOffenderAndStatus(offender, CheckinStatus.CREATED).single()
    assertNotEquals(cancelledCheckin.uuid, freshCheckin.uuid)
    val linkedAssignmentId = questionListAssignmentRepository.checkinAssignment(freshCheckin.id)
    assertEquals(pendingAssignment.listId, linkedAssignmentId)
    assertEquals(1, questionListAssignmentRepository.findAll().count { it.checkinId == freshCheckin.id })

    webTestClient.post()
      .uri("/v2/offenders/${offender.uuid}/update_details")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
      .bodyValue(OffenderDetailsUpdateRequest(checkinSchedule = scheduleUpdate))
      .exchange()
      .expectStatus().isOk

    val stillReusableCheckin = offenderCheckinRepository.findAllByOffenderAndStatus(offender, CheckinStatus.CREATED).single()
    assertEquals(freshCheckin.uuid, stillReusableCheckin.uuid)
  }

  @Test
  fun `scheduling today creates a checkin when first checkin is already today but none is active`() {
    val offender = offenderTemplate.copy(
      crn = "A123463",
      mode = CheckinMode.AD_HOC,
      checkinInterval = null,
      firstCheckin = clock.today(),
    ).toEntity()
    offenderRepository.save(offender)

    val cancelledDto = offenderCheckinService.debugCreateCheckin(offender, clock)
    val cancelledCheckin = offenderCheckinRepository.findByUuid(cancelledDto.uuid).orElseThrow()
    cancelledCheckin.status = CheckinStatus.CANCELLED
    offenderCheckinRepository.saveAndFlush(cancelledCheckin)

    val scheduleUpdate = CheckinScheduleWithQuestionsRequest(
      requestedBy = "BARRY.WHITE",
      firstCheckin = clock.today(),
      checkinInterval = null,
      mode = CheckinMode.AD_HOC,
    )
    webTestClient.post()
      .uri("/v2/offenders/${offender.uuid}/update_details")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
      .bodyValue(OffenderDetailsUpdateRequest(checkinSchedule = scheduleUpdate))
      .exchange()
      .expectStatus().isOk

    val activeCheckin = offenderCheckinRepository.findAllByOffenderAndStatus(offender, CheckinStatus.CREATED).single()
    assertNotEquals(cancelledCheckin.uuid, activeCheckin.uuid)
  }

  @Test
  fun `ad-hoc questions can be changed until the day before the checkin`() {
    val dueDate = clock.today().plusDays(1)
    val offender = offenderTemplate.copy(
      crn = "A123459",
      mode = CheckinMode.AD_HOC,
      checkinInterval = null,
      firstCheckin = dueDate,
    ).toEntity()
    offenderRepository.save(offender)

    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val originalRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    questionService.assignCustomQuestions(offender.crn, originalRequest)

    val replacementRequest = originalRequest.copy(
      questions = originalRequest.questions.map { item ->
        item.copy(params = mapOf("placeholders" to mapOf("thing" to "last-day replacement")))
      },
    )
    clock.advanceTo(dueDate.atStartOfDay(clock.zone).toInstant().minusSeconds(1))
    val replacement = questionService.assignCustomQuestions(offender.crn, replacementRequest)
    assertEquals(dueDate, replacement.expectedCheckinDate)
    assertEquals(replacement.listId, questionService.upcomingAssignment(offender).questionList)

    clock.advanceTo(dueDate.atStartOfDay(clock.zone).toInstant())
    assertThrows(BadArgumentException::class.java) {
      questionService.assignCustomQuestions(offender.crn, originalRequest)
    }
  }

  @Test
  fun `assign custom questions directly to a checkin validates question count`() {
    val offender = offenderTemplate.copy(
      crn = "A123458",
      mode = CheckinMode.AD_HOC,
      checkinInterval = null,
      firstCheckin = clock.today(),
    ).toEntity()
    offenderRepository.save(offender)
    val created = offenderCheckinService.debugCreateCheckin(offender, clock)
    val checkin = offenderCheckinRepository.findByUuid(created.uuid).orElseThrow()
    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val validRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)

    assertThrows(jakarta.validation.ConstraintViolationException::class.java) {
      questionService.assignCustomQuestionsToCheckin(checkin, validRequest.copy(questions = emptyList()))
    }
    assertThrows(jakarta.validation.ConstraintViolationException::class.java) {
      questionService.assignCustomQuestionsToCheckin(checkin, validRequest.copy(questions = validRequest.questions + validRequest.questions + validRequest.questions + validRequest.questions))
    }
  }

  @Test
  fun `QuestionService - upcoming questions for ad-hoc check-ins`() {
    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val dueDate = clock.today()
    val offender =
      offenderTemplate.copy(crn = "A000003", mode = CheckinMode.AD_HOC, checkinInterval = null, firstCheckin = dueDate)
        .toEntity()
    offenderRepository.save(offender)

    val assignment1 = questionService.upcomingAssignment(offender)
    assertEquals(dueDate, assignment1.expectedCheckinDate)

    val checkin1 = offenderCheckinService.debugCreateCheckin(offender, clock)
    offenderCheckinService.submitCheckin(checkin1.uuid, SubmitCheckinRequest(mapOf("version" to "whatever")))

    val assignment2 = questionService.upcomingAssignment(offender)
    assertNull(assignment2.expectedCheckinDate)

    offender.firstCheckin = clock.today().plusDays(4)
    offenderRepository.save(offender)

    val assignment3 = questionService.upcomingAssignment(offender)
    assertEquals(offender.firstCheckin, assignment3.expectedCheckinDate)
  }

  @Test
  fun `QuestionService - no checkin, correct expected check in date returned`() {
    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val dueDate = clock.today().plusDays(1)
    val offender = offenderTemplate.copy(crn = "A000003", firstCheckin = dueDate).toEntity()
    offenderRepository.save(offender)

    // Day before due date
    val addQuestionsRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    val assignment1 = questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)
    assertEquals(dueDate, assignment1.expectedCheckinDate)

    clock.advanceBy(Duration.ofDays(1))

    // Day = due date
    val assignment2 = questionService.upcomingAssignment(offender)
    assertEquals(dueDate, assignment2.expectedCheckinDate)

    val upcomingList = questionService.upcomingQuestionListItems(offender.crn, Language.ENGLISH)
    assertEquals(dueDate, upcomingList.expectedCheckinDate)

    val checkin = offenderCheckinService.debugCreateCheckin(offender, clock)
    val upcomingListWithCheckin = questionService.upcomingQuestionListItems(offender.crn, Language.ENGLISH)
    assertEquals(dueDate, upcomingListWithCheckin.expectedCheckinDate)

    clock.advanceBy(Duration.ofDays(1))

    // Day = due date + 1 day
    val upcomingDayAfterDueDate = questionService.upcomingQuestionListItems(offender.crn, Language.ENGLISH)
    assertEquals(dueDate, upcomingDayAfterDueDate.expectedCheckinDate)

    offenderCheckinService.submitCheckin(checkin.uuid, SubmitCheckinRequest(mapOf("version" to "whatever")))

    val upcomingAfterSubmission = questionService.upcomingQuestionListItems(offender.crn, Language.ENGLISH)
    assertEquals(dueDate.plusDays(offender.checkinInterval!!.toDays()), upcomingAfterSubmission.expectedCheckinDate)
  }

  @Test
  fun `QuestionService - submitted previous checkin, correct expected check in date returned`() {
    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val offender = offenderTemplate.copy(crn = "A000003", firstCheckin = clock.today().plusDays(1)).toEntity()
    offenderRepository.save(offender)

    clock.advanceBy(Duration.ofDays(1))

    // Day = 1st due date
    val checkin1 = offenderCheckinService.debugCreateCheckin(offender, clock)
    offenderCheckinService.submitCheckin(checkin1.uuid, SubmitCheckinRequest(mapOf("version" to "whatever")))

    val addQuestionsRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)

    clock.advanceBy(offender.checkinInterval!!)

    // Day = 2nd due date
    val nextDueDate = nextCheckinDay(offender, clock.today(), CheckinScheduleLowerBound.INCLUDE_TODAY)
    val info = questionListAssignmentRepository.upcomingAssignmentAndDueDate(
      offender.id,
      clock.today(),
      nextDueDate,
      checkinWindow.toDays(),
    )
    assertEquals(nextDueDate, info.dueDate)

    val assignment = questionService.upcomingAssignment(offender)
    assertEquals(nextDueDate, assignment.expectedCheckinDate)
  }

  @Test
  fun `QuestionService - assign custom questions when prev checkin had custom qs - success`() {
    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val addQuestionsRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    val defaultListId = questionListItemRepository.findDefaultListId()

    // ----- DAY 1
    val dueDate = clock.today()
    val offender = offenderTemplate.copy(crn = "A000003", firstCheckin = clock.today()).toEntity()
    offenderRepository.save(offender)

    // can't assign custom questions the same day a checkin is due
    assertThrows(BadArgumentException::class.java) {
      questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)
    }

    val checkin1 = offenderCheckinService.debugCreateCheckin(offender, clock)
    clock.advanceBy(Duration.ofHours(1))
    offenderCheckinService.submitCheckin(checkin1.uuid, SubmitCheckinRequest(mapOf("version" to "whatever")))

    val assignment1 = questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)
    val upcoming1 = questionListAssignmentRepository.upcomingAssignmentAndDueDate(
      offender.id,
      clock.today(),
      clock.today(),
      checkinWindow.toDays(),
    )
    assertNotNull(upcoming1)
    assertEquals(assignment1.listId, upcoming1.questionListId)
    assertEquals(dueDate.plusDays(offender.checkinInterval?.toDays()!!), upcoming1.dueDate)

    // ----- DAY 2
    clock.advanceBy(Duration.ofDays(1))
    val checkin2 = offenderCheckinService.debugCreateCheckin(offender, clock)
    // the assignment should have the checkin set
    val upcoming2 = questionListAssignmentRepository.upcomingAssignmentAndDueDate(
      offender.id,
      clock.today(),
      nextCheckinDay(offender, clock.today()),
      checkinWindow.toDays(),
    )
    assertEquals(assignment1.listId, upcoming2.questionListId)
    assertNotEquals(defaultListId, upcoming2.questionListId)
    assertEquals(checkin2.dueDate, upcoming2.dueDate)

    assertThrows(
      BadArgumentException::class.java,
      {
        questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)
      },
      "We can't assign questions until check-in is submitted/expired",
    )

    // Verify our assignment hasn't changed
    val upcoming3 = questionService.upcomingAssignment(offender)
    assertEquals(upcoming2.questionListId, upcoming3.questionList)

    val submission2 =
      offenderCheckinService.submitCheckin(checkin2.uuid, SubmitCheckinRequest(mapOf("version" to "whatever")))
    val upcoming4 = questionListAssignmentRepository.upcomingAssignmentAndDueDate(
      offender.id,
      clock.today(),
      nextCheckinDay(offender, clock.today()),
      checkinWindow.toDays(),
    )
    assertEquals(defaultListId, upcoming4.questionListId)
    assertEquals(checkin1.dueDate.plusDays(offender.checkinInterval?.toDays()!!), upcoming4.dueDate)

    val assignment2 = questionService.assignCustomQuestions(offender.crn, addQuestionsRequest)
    val upcoming5 = questionListAssignmentRepository.upcomingAssignmentAndDueDate(
      offender.id,
      clock.today(),
      nextCheckinDay(offender, clock.today()),
      checkinWindow.toDays(),
    )
    assertNotEquals(defaultListId, upcoming5.questionListId)
    assertEquals(assignment2.listId, upcoming5.questionListId)
  }

  @Test
  @Transactional
  fun `CustomQuestionsReminderJob - test our query`() {
    val offender1 =
      offenderTemplate.copy(crn = "A000001", firstCheckin = clock.today(), uuid = UUID.randomUUID()).toEntity()
    val offender2 =
      offenderTemplate.copy(crn = "A000002", firstCheckin = clock.today().plusDays(1), uuid = UUID.randomUUID())
        .toEntity()
    val offender3 =
      offenderTemplate.copy(crn = "A000003", firstCheckin = clock.today().plusDays(4), uuid = UUID.randomUUID())
        .toEntity()
    val offender4 =
      offenderTemplate.copy(crn = "A000004", firstCheckin = clock.today().plusDays(4), uuid = UUID.randomUUID())
        .toEntity()
    offenderRepository.saveAll(listOf(offender1, offender2, offender3, offender4))

    val templates = questionService.listQuestionTemplates(Language.ENGLISH, "BARRY.WHITE")
    val addQuestionsRequest = makeAssignCustomQuestionsRequest(Language.ENGLISH, templates)
    questionService.assignCustomQuestions(offender4.crn, addQuestionsRequest)

    val candidates = offenderRepository.findEligibleForPractitionerCustomQuestionsReminder(
      clock.today(),
      NotificationType.PractitionerCustomQuestionsReminder.name,
      clock.today().atStartOfDay(clock.zone).toInstant(),
    )
      .toList()
      .associateBy { it.crn }

    assertFalse(candidates.containsKey("A000001")) // too late to add questions
    assertFalse(candidates.containsKey("A000004")) // already has a question list assignment
    assertFalse(candidates.containsKey("A000002"))
    assertTrue(candidates.containsKey("A000003"))
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

private fun CheckinService.debugCreateCheckin(offender: Offender, clock: Clock): CheckinDto = this.createCheckinByCrn(
  CreateCheckinByCrnRequest(
    "BARRY.WHITE",
    offender.crn,
    clock.today(),
  ),
)
