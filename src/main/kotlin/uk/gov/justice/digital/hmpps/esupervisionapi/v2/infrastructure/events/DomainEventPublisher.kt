package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events

import org.slf4j.Logger
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.publish

@Service("v2DomainEventPublisher")
class DomainEventPublisher(
  private val hmppsQueueService: HmppsQueueService,
  private val objectMapper: ObjectMapper,
) {
  private val domainEventsTopic by lazy {
    hmppsQueueService.findByTopicId("hmppseventtopic") ?: throw IllegalStateException("hmppseventtopic not found")
  }
  fun publish(domainEvent: DomainEvent) {
    try {
      publishOrThrow(domainEvent)
    } catch (ex: Exception) {
      LOG.warn("Failed to publish event to outbound topic, eventType={}, message: {}", domainEvent.eventType, ex.message)
    }
  }

  /** Like [publish], but lets the failure through so the caller can retry. */
  fun publishOrThrow(domainEvent: DomainEvent) {
    val response = domainEventsTopic.publish(
      domainEvent.eventType,
      objectMapper.writeValueAsString(domainEvent),
    )
    LOG.info("Published event to outbound topic, eventType={}, messageId={}", domainEvent.eventType, response.messageId())
    LOG.debug("Event contains person reference, messageId={}, identifiers={}", response.messageId(), domainEvent.personReference?.identifiers)
  }

  companion object {
    val LOG: Logger = org.slf4j.LoggerFactory.getLogger(DomainEventPublisher::class.java)
  }
}
