package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events

import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.IOffenderEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderDeactivatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderReactivatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import java.util.concurrent.CompletableFuture

@Service
class OffenderEventListener(
  private val notificationService: NotificationService,
  private val outboxItemRepository: OutboxItemRepository,
) {

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  fun processEvent(event: IOffenderEvent): CompletableFuture<Void> {
    LOGGER.debug("processing offender event for offender CRN={} with status={}", event.offender.crn, event.offender.status.name)
    when (event) {
      is OffenderDeactivatedEvent -> notificationService.sendDeactivationCompletedNotifications(event)
      is OffenderReactivatedEvent -> notificationService.sendReactivationCompletedNotifications(event)
    }
    event.outboxItemCoords?.let { (type, id) ->
      val result = outboxItemRepository.markAsSent(type.name, id)
      LOGGER.info("offender CRN={}, marked outbox item {} as sent, updated records: {}", event.offender.crn, type to id, result)
    }

    return CompletableFuture.completedFuture(null)
  }

  companion object {
    private val LOGGER = logger<OffenderEventListener>()
  }
}
