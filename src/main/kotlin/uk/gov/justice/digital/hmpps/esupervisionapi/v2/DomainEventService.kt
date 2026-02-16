package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.PersonReference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import java.time.Clock
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Service responsible for constructing and publishing domain events
 * Handles URL construction and event metadata
 */
@Service
class DomainEventService(
  private val eventPublisher: DomainEventPublisher,
  private val clock: Clock,
  @Value("\${app.apiBaseUrl}") private val apiBaseUrl: String,
) {
  /**
   * Publish a domain event
   */
  fun publishDomainEvent(
    eventType: DomainEventType,
    uuid: UUID,
    crn: String,
    description: String,
    occurredAt: ZonedDateTime? = null,
  ) {
    LOGGER.debug(">>> Initiating {} event for uuid={}, crn={}", eventType.eventTypeName, uuid, crn)
    val detailUrl = "$apiBaseUrl/v2/events/${eventType.pathSegment}/$uuid"

    try {
      val event = DomainEvent(
        eventType = eventType.type,
        detailUrl = detailUrl,
        occurredAt = occurredAt ?: clock.instant().atZone(clock.zone),
        description = description,
        personReference = PersonReference(listOf(PersonReference.PersonIdentifier("CRN", crn))),
      )

      eventPublisher.publish(event)
      LOGGER.info("Published domain event: eventType={}, crn={}, detailUrl={}", eventType.type, crn, detailUrl)
    } catch (e: Exception) {
      LOGGER.error(
        "Failed to publish domain event: {}",
        PiiSanitizer.sanitizeMessage(e.message ?: "Unknown error", crn, null) + " [eventType=${eventType.type}]",
      )
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(DomainEventService::class.java)
  }
}
