package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events

import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinReviewedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinSubmittedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ICheckinEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import java.util.concurrent.CompletableFuture

@Service
class CheckinEventsListener(
  private val notificationService: NotificationV2Service,
  private val transactionTemplate: TransactionTemplate,
  private val outboxItemRepository: OutboxItemRepository,
) {

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  fun processEvent(event: ICheckinEvent): CompletableFuture<Void> {
    LOGGER.debug("processing checkin event for checkin uuid={} with status={}", event.checkin.uuid, event.checkin.status)
    when (event) {
      is CheckinSubmittedEvent -> notificationService.sendCheckinSubmittedNotifications(event)
      is CheckinReviewedEvent -> notificationService.sendCheckinReviewedNotifications(event)
    }
    event.outboxItemCoords?.let { (type, id) ->
      val result = outboxItemRepository.markAsSent(type.name, id)
      LOGGER.info("checkin={}, marked outbox item {} as sent, updated records: {}", event.checkin.uuid, type to id, result)
    }
    return CompletableFuture.completedFuture(null)
  }

  companion object {
    private val LOGGER = logger<CheckinEventsListener>()
  }
}
