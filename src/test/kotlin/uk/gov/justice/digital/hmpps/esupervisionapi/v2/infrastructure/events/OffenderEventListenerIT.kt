package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderDeactivatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.LocalDate
import java.util.UUID.randomUUID
import java.util.concurrent.TimeUnit

class OffenderEventListenerIT : IntegrationTestBase() {

  @Autowired
  private lateinit var offenderEventListener: OffenderEventListener

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var offenderPersistenceService: OffenderPersistenceService

  @Autowired
  private lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired private lateinit var offenderSetupRepository: OffenderSetupRepository

  @AfterEach
  fun cleanUp() {
    offenderSetupRepository.deleteAll()
    outboxItemRepository.deleteAll()
    offenderRepository.deleteAll()
    offenderRepository.flush()
  }

  @Test
  fun `processEvent - offender deactivated - mark outbox item as sent - success`() {
    var offender = offenderTemplate.copy(firstCheckin = LocalDate.now(), status = OffenderStatus.VERIFIED).toEntity()
    offender = offenderRepository.save(offender)

    offender.status = OffenderStatus.INACTIVE
    val setup = Pair(1L, randomUUID())
    val event = OffenderDeactivatedEvent(
      offenderId = offender.id,
      offender = offender.dto(),
      auditEventType = OffenderAuditEventType.OFFENDER_DEACTIVATED,
      setup = setup,
      activeEventNumber = null,
    )
    offenderPersistenceService.offenderDeactivation(offender, event)

    offenderEventListener.processEvent(event).get(2, TimeUnit.SECONDS)

    val outboxItem = outboxItemRepository.findByTypeAndEntityId(OutboxItemType.OFFENDER_DEACTIVATED, setup.first).orElseThrow()
    assertEquals(OutboxItemStatus.SENT, outboxItem.status)
  }

  @TestConfiguration
  class TestConfig {
    @Bean
    @Primary
    fun notificationV2Service(): NotificationService = mock()
  }
}
