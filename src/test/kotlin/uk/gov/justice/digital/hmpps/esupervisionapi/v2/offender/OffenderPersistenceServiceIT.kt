package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
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

@TestPropertySource(properties = ["app.features.esup-2305=true"])
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
      offender = offender.dto().copy(status = OffenderStatus.INACTIVE, updatedAt = clock.instant()),
      auditEventType = OffenderAuditEventType.OFFENDER_DEACTIVATED,
      setup = null,
      activeEventNumber = null,
    )

    offenderPersistenceService.offenderDeactivation(offender, event)

    val updatedCheckin = checkinRepository.findById(checkin.id).orElseThrow()
    assertEquals(CheckinStatus.CANCELLED, updatedCheckin.status)

    val assignments = questionListAssignmentRepository.findAll()
    assertEquals(0, assignments.size)
  }

  @Test
  fun `stale backfill deactivation does not deactivate an offender whose event was selected concurrently`() {
    val offender = offenderTemplate.copy(firstCheckin = LocalDate.now(), status = OffenderStatus.VERIFIED).toEntity().apply {
      currentEvent = 202L
    }
    offenderRepository.save(offender)
    val staleEvent = OffenderDeactivatedEvent(
      offenderId = offender.id,
      offender = offender.dto().copy(status = OffenderStatus.INACTIVE, currentEvent = null),
      auditEventType = OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS,
      setup = null,
      activeEventNumber = null,
      reason = "Automatically deactivated: no active sentence in NDelius",
    )

    offenderPersistenceService.offenderDeactivation(offender, staleEvent)

    val persisted = offenderRepository.findById(offender.id).orElseThrow()
    assertEquals(OffenderStatus.VERIFIED, persisted.status)
    assertEquals(202L, persisted.currentEvent)
  }

  @Test
  fun `manual deactivation still applies when the selected event changes concurrently`() {
    val offender = offenderTemplate.copy(firstCheckin = LocalDate.now(), status = OffenderStatus.VERIFIED).toEntity().apply {
      currentEvent = 202L
    }
    offenderRepository.save(offender)
    val staleEvent = OffenderDeactivatedEvent(
      offenderId = offender.id,
      offender = offender.dto().copy(status = OffenderStatus.INACTIVE, currentEvent = 101L),
      auditEventType = OffenderAuditEventType.OFFENDER_DEACTIVATED,
      setup = null,
      activeEventNumber = 101L,
      reason = "Practitioner requested deactivation",
    )

    offenderPersistenceService.offenderDeactivation(offender, staleEvent)

    val persisted = offenderRepository.findById(offender.id).orElseThrow()
    assertEquals(OffenderStatus.INACTIVE, persisted.status)
    assertEquals(202L, persisted.currentEvent)
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
    assertEquals(1002L, offenderRepository.findById(offender.id).orElseThrow().currentEvent)

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

    val deactivationEvent = OffenderDeactivatedEvent(
      offenderId = offender.id,
      offender = offender.dto().copy(status = OffenderStatus.INACTIVE, updatedAt = clock.instant()),
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
    offenderPersistenceService.offenderDeactivation(
      offenderAfterReactivation,
      deactivationEvent.copy(
        offender = offenderAfterReactivation.dto().copy(status = OffenderStatus.INACTIVE, updatedAt = clock.instant()),
        setup = SetupInfo.from(setup2),
        reason = "deactivate 2",
      ),
    )

    val offenderAfterSecondDeactivation = offenderRepository.findById(offender.id).orElseThrow()
    val reactivation = offenderPersistenceService.offenderReactivation(offenderAfterSecondDeactivation.id, partialEvent.copy(reason = "reactivate 2"))
    assertNotNull(reactivation)
  }
}
