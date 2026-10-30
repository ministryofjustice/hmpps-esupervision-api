package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderDeactivatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PartialOffenderReactivatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignment
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignmentRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SetupInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.UUID.randomUUID

class OffenderPersistenceServiceIT : IntegrationTestBase() {

  @Autowired
  private lateinit var offenderPersistenceService: OffenderPersistenceService

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  private lateinit var questionListAssignmentRepository: QuestionListAssignmentRepository

  @Autowired
  private lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired
  private lateinit var offenderSetupRepository: OffenderSetupRepository

  @Autowired
  private lateinit var clock: Clock

  // Before as well as after: the outbox assertions count every item, so one left by another test class would fail them.
  @BeforeEach
  @AfterEach
  fun cleanUp() {
    questionListAssignmentRepository.deleteAll()
    checkinRepository.deleteAll()
    offenderSetupRepository.deleteAll()
    offenderSetupRepository.flush()
    offenderRepository.deleteAll()
    offenderRepository.flush()
    outboxItemRepository.deleteAll()
  }

  @Test
  fun `batch setup lookup returns the latest setup for each requested offender`() {
    val offenders = (1..4).map {
      offenderRepository.save(offenderTemplate.copy(uuid = randomUUID(), crn = "X00000$it").toEntity())
    }
    val now = clock.instant()
    fun saveSetup(index: Int, createdAt: Instant) = offenderSetupRepository.saveAndFlush(
      OffenderSetup(randomUUID(), offenders[index], offenders[index].practitionerId, createdAt),
    )
    saveSetup(0, now.minusSeconds(60))
    val latestFirst = saveSetup(0, now)
    saveSetup(1, now)
    val latestSecond = saveSetup(1, now)
    saveSetup(3, now)

    latestSecond.incrementSetupCounter()
    offenderSetupRepository.saveAndFlush(latestSecond)

    val setups = offenderSetupRepository.findLatestByOffenderIdIn(offenders.take(3).map { it.id })
      .associateBy { it.offenderId }

    assertEquals(setOf(offenders[0].id, offenders[1].id), setups.keys)
    assertEquals(SetupInfo.from(latestFirst), SetupInfo.from(setups.getValue(offenders[0].id)))
    assertEquals(SetupInfo.from(latestSecond), SetupInfo.from(setups.getValue(offenders[1].id)))
    assertEquals(2, setups.getValue(offenders[1].id).setupCounter)
  }

  @Test
  fun `offenderDeactivation cancels checkins and deletes upcoming question assignments`() {
    var offender = offenderTemplate.copy(firstCheckin = LocalDate.now(), status = OffenderStatus.VERIFIED).toEntity()
    offender = offenderRepository.save(offender)

    var checkin = OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = offender,
      status = CheckinStatus.CREATED,
      dueDate = LocalDate.now(),
      createdAt = Instant.now(),
      createdBy = "test_user",
    )
    checkin = checkinRepository.save(checkin)

    var assignment = QuestionListAssignment(
      questionListId = 1L,
      offenderId = offender.id,
      checkinId = null,
      created_at = Instant.now(),
      updatedAt = Instant.now(),
    )
    assignment = questionListAssignmentRepository.save(assignment)

    val event = OffenderDeactivatedEvent(
      offenderId = offender.id,
      offender = offender.dto(),
      auditEventType = OffenderAuditEventType.OFFENDER_DEACTIVATED,
      setup = null,
      activeEventNumber = null,
    )

    offenderPersistenceService.offenderDeactivation(offender, event)

    val updatedOffender = offenderRepository.findById(offender.id).orElseThrow()
    assertEquals(OffenderStatus.INACTIVE, updatedOffender.status)
    assertNotEquals(offender.updatedAt, updatedOffender.updatedAt)

    val updatedCheckin = checkinRepository.findById(checkin.id).orElseThrow()
    assertEquals(CheckinStatus.CANCELLED, updatedCheckin.status)

    val assignments = questionListAssignmentRepository.findAll()
    assertEquals(0, assignments.size)
  }

  @ParameterizedTest
  @EnumSource(value = OffenderStatus::class, names = ["INITIAL", "INACTIVE"])
  fun `offenderDeactivation skips downstream updates when the stored status is not VERIFIED`(status: OffenderStatus) {
    val originalUpdatedAt = Instant.parse("2026-10-28T10:00:00Z")
    val offender = offenderRepository.saveAndFlush(
      offenderTemplate.copy(status = status).toEntity().apply { updatedAt = originalUpdatedAt },
    )
    val (checkin, assignment) = savePendingWork(offender)
    val setup = offenderSetupRepository.saveAndFlush(OffenderSetup(randomUUID(), offender, offender.practitionerId, clock.instant()))
    offender.status = OffenderStatus.INACTIVE
    offender.updatedAt = originalUpdatedAt.plusSeconds(60)
    val event = deactivationEvent(offender).copy(setup = SetupInfo.from(setup))

    assertNull(offenderPersistenceService.offenderDeactivation(offender, event))

    val persistedOffender = offenderRepository.findById(offender.id).orElseThrow()
    assertEquals(status, persistedOffender.status)
    assertEquals(originalUpdatedAt, persistedOffender.updatedAt)
    assertEquals(CheckinStatus.CREATED, checkinRepository.findById(checkin.id).orElseThrow().status)
    assertEquals(assignment.id, questionListAssignmentRepository.findById(assignment.id).orElseThrow().id)
    assertTrue(outboxItemRepository.findByTypeAndEntityId(OutboxItemType.OFFENDER_DEACTIVATED, setup.id).isEmpty)
  }

  @Test
  fun `offenderDeactivation does not overwrite unrelated fields from a stale entity`() {
    val offender = offenderRepository.saveAndFlush(offenderTemplate.copy(status = OffenderStatus.VERIFIED).toEntity())
    val latestOffender = offenderRepository.findById(offender.id).orElseThrow()
    latestOffender.practitionerId = "NEW_PRACTITIONER"
    latestOffender.firstCheckin = LocalDate.of(2026, 11, 1)
    offenderRepository.saveAndFlush(latestOffender)
    offender.status = OffenderStatus.INACTIVE

    offenderPersistenceService.offenderDeactivation(offender, deactivationEvent(offender))

    val persistedOffender = offenderRepository.findById(offender.id).orElseThrow()
    assertEquals(OffenderStatus.INACTIVE, persistedOffender.status)
    assertEquals(latestOffender.practitionerId, persistedOffender.practitionerId)
    assertEquals(latestOffender.firstCheckin, persistedOffender.firstCheckin)
  }

  @Test
  fun `repeated offenderDeactivation leaves the timestamp and subsequent pending work unchanged`() {
    val offender = offenderRepository.saveAndFlush(offenderTemplate.copy(status = OffenderStatus.VERIFIED).toEntity())
    offender.status = OffenderStatus.INACTIVE
    offender.updatedAt = Instant.parse("2026-10-29T10:00:00Z")
    val event = deactivationEvent(offender)
    assertNotNull(offenderPersistenceService.offenderDeactivation(offender, event))
    val firstDeactivatedAt = offenderRepository.findById(offender.id).orElseThrow().updatedAt
    val (checkin, assignment) = savePendingWork(offender)
    offender.updatedAt = offender.updatedAt.plusSeconds(60)

    assertNull(offenderPersistenceService.offenderDeactivation(offender, event))

    val persistedOffender = offenderRepository.findById(offender.id).orElseThrow()
    assertEquals(OffenderStatus.INACTIVE, persistedOffender.status)
    assertEquals(firstDeactivatedAt, persistedOffender.updatedAt)
    assertEquals(CheckinStatus.CREATED, checkinRepository.findById(checkin.id).orElseThrow().status)
    assertEquals(assignment.id, questionListAssignmentRepository.findById(assignment.id).orElseThrow().id)
  }

  private fun deactivationEvent(offender: Offender) = OffenderDeactivatedEvent(
    offenderId = offender.id,
    offender = offender.dto(),
    auditEventType = OffenderAuditEventType.OFFENDER_DEACTIVATED,
    setup = null,
    activeEventNumber = null,
  )

  private fun savePendingWork(offender: Offender): Pair<OffenderCheckin, QuestionListAssignment> {
    val checkin = checkinRepository.saveAndFlush(
      OffenderCheckin(
        uuid = randomUUID(),
        offender = offender,
        status = CheckinStatus.CREATED,
        dueDate = LocalDate.now(clock),
        createdAt = clock.instant(),
        createdBy = "test_user",
      ),
    )
    val assignment = questionListAssignmentRepository.saveAndFlush(
      QuestionListAssignment(
        questionListId = 1L,
        offenderId = offender.id,
        checkinId = null,
        created_at = clock.instant(),
        updatedAt = clock.instant(),
      ),
    )
    return checkin to assignment
  }

  @Test
  fun `offenderReactivation tests`() {
    var offender = offenderTemplate.copy(firstCheckin = LocalDate.now(), status = OffenderStatus.VERIFIED).toEntity()
    offender = offenderRepository.save(offender)

    val event1 = PartialOffenderReactivatedEvent(
      offenderId = offender.id,
      offender = offender.dto(),
      currentEvent = 1001L,
      reason = "test",
    )

    var result = offenderPersistenceService.offenderReactivation(offender.id, event1)
    assertNull(result)

    // pretend we deactivated the offender
    offender.status = OffenderStatus.INACTIVE
    offenderRepository.save(offender)

    val event2 = event1.copy(offender = offender.dto(), currentEvent = 1002L)
    result = offenderPersistenceService.offenderReactivation(offender.id, event2)

    assertNotNull(result)
    assertEquals(OffenderStatus.VERIFIED, result.offender.status)

    val outboxItems = outboxItemRepository.findAll()
    assertEquals(1, outboxItems.size)
    assertEquals(1, outboxItems.filter { it.type == OutboxItemType.OFFENDER_REACTIVATED }.size)
  }

  @Test
  fun `repeated reactivation does not create duplicate outbox items`() {
    var offender = offenderTemplate.copy(firstCheckin = LocalDate.now(), status = OffenderStatus.VERIFIED).toEntity()
    offenderRepository.save(offender)
    assert(offenderSetupRepository.findByOffender(offender).isEmpty, { "Some test possibly didn't clean up correctly" })
    val setup = OffenderSetup(randomUUID(), offender, offender.practitionerId, clock.instant())
    offenderSetupRepository.save(setup)

    offender.status = OffenderStatus.INACTIVE
    val deactivationEvent = OffenderDeactivatedEvent(
      offenderId = offender.id,
      offender = offender.dto(),
      auditEventType = OffenderAuditEventType.OFFENDER_DEACTIVATED,
      setup = SetupInfo.from(setup),
      activeEventNumber = 101L,
      reason = "deactivate 1",
    )
    offenderPersistenceService.offenderDeactivation(offender, deactivationEvent)

    val dtoAfterDeactivation = offender.dto()
    val dtoForReactivation = dtoAfterDeactivation.copy(firstCheckin = dtoAfterDeactivation.firstCheckin?.plusWeeks(1))
    val partialEvent = PartialOffenderReactivatedEvent(
      offenderId = offender.id,
      offender = dtoForReactivation,
      currentEvent = 102L,
      reason = "reactivate 1",
    )
    val event = offenderPersistenceService.offenderReactivation(offender.id, partialEvent)
    assertNotNull(event)

    val offenderAfterReactivation = offenderRepository.findById(offender.id).orElseThrow()
    assertEquals(dtoForReactivation.firstCheckin, offenderAfterReactivation.firstCheckin)
    assertEquals(OffenderStatus.VERIFIED, offenderAfterReactivation.status)

    val setup2 = offenderSetupRepository.findByOffender(offender).get()
    offenderAfterReactivation.status = OffenderStatus.INACTIVE
    offenderPersistenceService.offenderDeactivation(
      offenderAfterReactivation,
      deactivationEvent.copy(
        offender = offenderAfterReactivation.dto(),
        setup = SetupInfo.from(setup2),
        reason = "deactivate 2",
      ),
    )

    val offenderAfterSecondDeactivation = offenderRepository.findById(offender.id).orElseThrow()
    val reactivation = offenderPersistenceService.offenderReactivation(offenderAfterSecondDeactivation.id, partialEvent.copy(reason = "reactivate 2"))
    assertNotNull(reactivation)
  }
}
