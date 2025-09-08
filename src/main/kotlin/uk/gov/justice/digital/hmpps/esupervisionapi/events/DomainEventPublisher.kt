package uk.gov.justice.digital.hmpps.esupervisionapi.events

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.springframework.stereotype.Service
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.publish

@Service
class DomainEventPublisher(
  private val hmppsQueueService: HmppsQueueService,
  private val objectMapper: ObjectMapper,
) {
  private val domainEventsTopic by lazy {
    hmppsQueueService.findByTopicId("hmppseventtopic") ?: throw IllegalStateException("hmppseventtopic not found")
  }
  fun publish(domainEvent: DomainEvent) {
    val response = domainEventsTopic.publish(
      domainEvent.eventType,
      objectMapper.writeValueAsString(domainEvent),
    )
    LOG.info("Published event to outbound topic, messageId={}, response={}", response.messageId(), response)
  }

  companion object {
    val LOG: Logger = org.slf4j.LoggerFactory.getLogger(DomainEventPublisher::class.java)
  }
}
