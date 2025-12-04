package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.springframework.stereotype.Service
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
      val response = domainEventsTopic.publish(
        domainEvent.eventType,
        objectMapper.writeValueAsString(domainEvent),
      )
      LOG.info("Published event to outbound topic, eventType={}, messageId={}", domainEvent.eventType, response.messageId())
      LOG.debug("Event contains person reference, messageId={}, identifiers={}", response.messageId(), domainEvent.personReference?.identifiers)
    } catch (ex: Exception) {
      LOG.warn("Failed to publish event to outbound topic, eventType={}, message: {}", domainEvent.eventType, ex.message)
    }
  }

  companion object {
    val LOG: Logger = org.slf4j.LoggerFactory.getLogger(DomainEventPublisher::class.java)
  }
}
