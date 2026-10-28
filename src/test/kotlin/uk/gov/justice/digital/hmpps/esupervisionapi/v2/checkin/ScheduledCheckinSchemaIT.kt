package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinLinkStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignmentRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.question.DebugQuestionsRepository
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * ESUP-2223: database behaviour of upcoming (SCHEDULED) check-ins and their activation.
 */
class ScheduledCheckinSchemaIT : IntegrationTestBase() {

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  private lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired
  private lateinit var questionListAssignmentRepository: QuestionListAssignmentRepository

  @Autowired
  private lateinit var questionListItemRepository: DebugQuestionsRepository

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  private lateinit var transactionTemplate: TransactionTemplate

  @Autowired
  private lateinit var clock: Clock

  @AfterEach
  fun tearDown() {
    questionListAssignmentRepository.deleteAll()
    jdbcTemplate.update("TRUNCATE TABLE outbox_items RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_checkin_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_v2 RESTART IDENTITY CASCADE")
  }

  @Test
  fun `inserting a SCHEDULED check-in neither creates an outbox item nor binds the question assignment`() {
    val offender = saveOffender()
    val listId = questionListItemRepository.findDefaultListId()!!
    transactionTemplate.executeWithoutResult { questionListAssignmentRepository.createAssignment(offender.id, listId) }

    val checkin = checkinRepository.save(checkin(offender, CheckinStatus.SCHEDULED, clock.today().plusDays(3)))

    assertTrue(outboxItemRepository.findByTypeAndEntityId(OutboxItemType.CHECKIN_CREATED, checkin.id).isEmpty)
    assertNull(questionListAssignmentRepository.checkinAssignment(checkin.id))
  }

  @Test
  fun `activating a SCHEDULED check-in creates the outbox item and binds the question assignment`() {
    val offender = saveOffender()
    val listId = questionListItemRepository.findDefaultListId()!!
    transactionTemplate.executeWithoutResult { questionListAssignmentRepository.createAssignment(offender.id, listId) }
    val checkin = checkinRepository.save(checkin(offender, CheckinStatus.SCHEDULED, clock.today()))

    checkin.status = CheckinStatus.CREATED
    checkinRepository.saveAndFlush(checkin)

    assertTrue(outboxItemRepository.findByTypeAndEntityId(OutboxItemType.CHECKIN_CREATED, checkin.id).isPresent)
    assertEquals(listId, questionListAssignmentRepository.checkinAssignment(checkin.id))
  }

  @Test
  fun `cancelling a SCHEDULED check-in creates no outbox item`() {
    val offender = saveOffender()
    val checkin = checkinRepository.save(checkin(offender, CheckinStatus.SCHEDULED, clock.today().plusDays(3)))

    checkin.status = CheckinStatus.CANCELLED
    checkinRepository.saveAndFlush(checkin)

    assertEquals(0, outboxItemRepository.count())
  }

  @Test
  fun `a SCHEDULED check-in due today is not treated as today's completed check-in`() {
    val today = clock.today()
    val offender = saveOffender(firstCheckin = today)
    checkinRepository.save(checkin(offender, CheckinStatus.SCHEDULED, today))

    val info = questionListAssignmentRepository.upcomingAssignmentAndDueDate(offender.id, today, today, 3)

    // with a completed check-in today this would be today + interval
    assertEquals(today, info.dueDate)
  }

  @Test
  fun `practitioner check-in list excludes SCHEDULED check-ins`() {
    val offender = saveOffender()
    val created = checkinRepository.save(checkin(offender, CheckinStatus.CREATED, clock.today()))
    checkinRepository.save(checkin(offender, CheckinStatus.SCHEDULED, clock.today().plusDays(7)))

    val page = checkinRepository.findAllByCreatedBy(offender.practitionerId, offender.uuid, PageRequest.of(0, 10))

    assertEquals(listOf(created.uuid), page.content.map { it.uuid })
    assertEquals(1, page.totalElements)
  }

  @Test
  fun `link status round-trips and defaults to null`() {
    val offender = saveOffender()
    val legacy = checkinRepository.save(checkin(offender, CheckinStatus.CREATED, clock.today()))
    val withLink = checkin(offender, CheckinStatus.CREATED, clock.today()).apply {
      linkStatus = CheckinLinkStatus.NOT_SENT
      linkStatusUpdatedAt = clock.instant()
    }
    checkinRepository.saveAndFlush(withLink)

    assertNull(checkinRepository.findByUuid(legacy.uuid).get().linkStatus)
    val reloaded = checkinRepository.findByUuid(withLink.uuid).get()
    assertEquals(CheckinLinkStatus.NOT_SENT, reloaded.linkStatus)
    assertFalse(reloaded.linkStatusUpdatedAt == null)
  }

  @Test
  fun `SCHEDULED can only become CREATED or CANCELLED`() {
    assertEquals(
      setOf(CheckinStatus.CREATED, CheckinStatus.CANCELLED),
      CheckinStatus.entries.filter { CheckinStatus.SCHEDULED.canTransitionTo(it) }.toSet(),
    )
  }

  private fun saveOffender(firstCheckin: LocalDate = clock.today().minusDays(5)): Offender = offenderRepository.save(offenderTemplate.copy(uuid = UUID.randomUUID(), firstCheckin = firstCheckin).toEntity())

  private fun checkin(offender: Offender, status: CheckinStatus, dueDate: LocalDate) = OffenderCheckin(
    uuid = UUID.randomUUID(),
    offender = offender,
    status = status,
    dueDate = dueDate,
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
  )
}
