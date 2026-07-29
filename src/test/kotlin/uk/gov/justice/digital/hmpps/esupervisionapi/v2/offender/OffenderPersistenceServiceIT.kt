package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderDeactivatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PartialOffenderReactivatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignment
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignmentRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

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

    offender.status = OffenderStatus.INACTIVE
    val event = OffenderDeactivatedEvent(
      offenderId = offender.id,
      offender = offender.dto(),
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
  fun `offenderReactivation tests`() {
    var offender = offenderTemplate.copy(firstCheckin = LocalDate.now(), status = OffenderStatus.VERIFIED).toEntity()
    offender = offenderRepository.save(offender)

    val event1 = PartialOffenderReactivatedEvent(
      offenderId = offender.id,
      offender = offender.dto(),
      currentEvent = 1001L,
      reason = "test",
    )

    var result = offenderPersistenceService.offenderReactivation(offender, event1)
    assertNull(result)

    offender.status = OffenderStatus.INACTIVE
    offenderRepository.save(offender)

    offender.status = OffenderStatus.VERIFIED
    val event2 = event1.copy(offender = offender.dto(), currentEvent = 1002L)
    result = offenderPersistenceService.offenderReactivation(offender, event2)

    assertNotNull(result)
    assertEquals(OffenderStatus.VERIFIED, result.offender.status)

    val outboxItems = outboxItemRepository.findAll()
    assertEquals(2, outboxItems.size) // one for saving inactive, one for the re-activation
    assertEquals(1, outboxItems.filter { it.type == OutboxItemType.OFFENDER_SETUP_COMPLETE }.size)
  }
}
