package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderDeactivatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignmentRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SetupInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Instant
import java.util.Optional
import java.util.UUID.randomUUID

class OffenderPersistenceServiceTest {
  private val offenderRepository: OffenderRepository = mock()
  private val offenderSetupRepository: OffenderSetupRepository = mock()
  private val checkinRepository: OffenderCheckinRepository = mock()
  private val outboxItemRepository: OutboxItemRepository = mock()
  private val questionListAssignmentRepository: QuestionListAssignmentRepository = mock()
  private val eventAuditService: EventAuditService = mock()
  private val entityManager: EntityManager = mock()
  private val appEventPublisher: ApplicationEventPublisher = mock()
  private val service = OffenderPersistenceService(
    offenderRepository,
    offenderSetupRepository,
    checkinRepository,
    outboxItemRepository,
    questionListAssignmentRepository,
    eventAuditService,
    entityManager,
    appEventPublisher,
  )
  private val offender = offenderTemplate.copy(status = OffenderStatus.INACTIVE).toEntity().apply {
    updatedAt = Instant.parse("2026-10-29T10:00:00Z")
  }
  private val setup = SetupInfo.from(OffenderSetup(randomUUID(), offender, offender.practitionerId, offender.updatedAt))
  private val event = OffenderDeactivatedEvent(
    offenderId = offender.id,
    offender = offender.dto(),
    auditEventType = OffenderAuditEventType.OFFENDER_DEACTIVATED,
    setup = setup,
    activeEventNumber = null,
    reason = "No active events",
    sensitive = true,
  )

  @Test
  fun `skips every downstream operation when no VERIFIED row is updated`() {
    whenever(offenderRepository.deactivateIfVerified(offender.id)).thenReturn(Optional.empty())

    assertNull(service.offenderDeactivation(offender, event))

    verify(offenderRepository).deactivateIfVerified(offender.id)
    verifyNoMoreInteractions(offenderRepository)
    verifyNoInteractions(
      offenderSetupRepository,
      checkinRepository,
      outboxItemRepository,
      questionListAssignmentRepository,
      eventAuditService,
      entityManager,
      appEventPublisher,
    )
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `performs downstream operations once for repeated deactivation`(hasSetup: Boolean) {
    whenever(offenderRepository.deactivateIfVerified(offender.id))
      .thenReturn(Optional.of(offender), Optional.empty())
    val deactivationEvent = event.copy(setup = if (hasSetup) setup else null)

    assertSame(offender, service.offenderDeactivation(offender, deactivationEvent))
    assertNull(service.offenderDeactivation(offender, deactivationEvent))

    inOrder(offenderRepository, outboxItemRepository, questionListAssignmentRepository, checkinRepository, eventAuditService, appEventPublisher, entityManager) {
      verify(offenderRepository).deactivateIfVerified(offender.id)
      verify(entityManager).refresh(offender)
      if (hasSetup) {
        verify(outboxItemRepository).addOutboxItem(OutboxItemType.OFFENDER_DEACTIVATED, setup.primaryKey)
      }
      verify(questionListAssignmentRepository).deleteUpcomingAssignment(offender.id)
      verify(checkinRepository).updateStatusForOffender(offender, CheckinStatus.CREATED, CheckinStatus.CANCELLED)
      verify(eventAuditService).recordOffenderEvent(event.auditEventType, event.offender, event.offender.personalDetails, event.reason, event.sensitive)
      verify(appEventPublisher).publishEvent(deactivationEvent)
      verify(offenderRepository).deactivateIfVerified(offender.id)
    }
    verifyNoMoreInteractions(offenderRepository, outboxItemRepository, questionListAssignmentRepository, checkinRepository, eventAuditService, appEventPublisher)
  }

  @Test
  fun `does not swallow conditional update failures or perform downstream work`() {
    val failure = IllegalStateException("Update failed")
    whenever(offenderRepository.deactivateIfVerified(offender.id)).thenThrow(failure)

    assertEquals(failure, assertThrows<IllegalStateException> { service.offenderDeactivation(offender, event) })

    verifyNoInteractions(outboxItemRepository, questionListAssignmentRepository, checkinRepository, eventAuditService, appEventPublisher)
  }
}
