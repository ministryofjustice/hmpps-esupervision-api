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

/**
 * Service responsible for constructing and publishing domain events
 * Handles URL construction and event metadata
 */
@Service
class DomainEventService(
  private val eventPublisher: DomainEventPublisher,
  private val clock: Clock,
  @Value("\${app.hostedAt}") private val hostedAt: String,
) {
  /**
   * Publish a setup completed event
   */
  fun publishSetupCompleted(offender: OffenderV2) {
    val detailUrl = "$hostedAt/v2/events/setup-completed/${offender.uuid}"
    publishEvent(
      eventType = DomainEventType.V2_SETUP_COMPLETED,
      crn = offender.crn,
      detailUrl = detailUrl,
      description = "Practitioner completed setup for offender ${offender.crn}",
    )
  }

  /**
   * Publish a checkin created event
   */
  fun publishCheckinCreated(checkin: OffenderCheckinV2) {
    val detailUrl = "$hostedAt/v2/events/checkin-created/${checkin.uuid}"
    publishEvent(
      eventType = DomainEventType.V2_CHECKIN_CREATED,
      crn = checkin.offender.crn,
      detailUrl = detailUrl,
      description = "Check-in created for ${checkin.offender.crn} with due date ${checkin.dueDate}",
    )
  }

  /**
   * Publish a checkin submitted event
   */
  fun publishCheckinSubmitted(checkin: OffenderCheckinV2) {
    val detailUrl = "$hostedAt/v2/events/checkin-submitted/${checkin.uuid}"
    publishEvent(
      eventType = DomainEventType.V2_CHECKIN_SUBMITTED,
      crn = checkin.offender.crn,
      detailUrl = detailUrl,
      description = "Check-in submitted for ${checkin.offender.crn}",
    )
  }

  /**
   * Publish a checkin reviewed event
   */
  fun publishCheckinReviewed(checkin: OffenderCheckinV2) {
    val detailUrl = "$hostedAt/v2/events/checkin-reviewed/${checkin.uuid}"
    publishEvent(
      eventType = DomainEventType.V2_CHECKIN_REVIEWED,
      crn = checkin.offender.crn,
      detailUrl = detailUrl,
      description = "Check-in reviewed for ${checkin.offender.crn} by ${checkin.reviewedBy}",
    )
  }

  /**
   * Publish a checkin expired event
   */
  fun publishCheckinExpired(checkin: OffenderCheckinV2) {
    val detailUrl = "$hostedAt/v2/events/checkin-expired/${checkin.uuid}"
    publishEvent(
      eventType = DomainEventType.V2_CHECKIN_EXPIRED,
      crn = checkin.offender.crn,
      detailUrl = detailUrl,
      description = "Check-in expired for ${checkin.offender.crn} (due date was ${checkin.dueDate})",
    )
  }

  /**
   * Generic publish method for custom events
   */
  fun publishEvent(
    eventType: DomainEventType,
    crn: String,
    detailUrl: String,
    description: String,
  ) {
    try {
      val event = DomainEvent(
        eventType = eventType.type,
        detailUrl = detailUrl,
        occurredAt = clock.instant().atZone(clock.zone),
        description = description,
        personReference = PersonReference(listOf(PersonReference.PersonIdentifier("CRN", crn))),
      )

      eventPublisher.publish(event)
      LOGGER.info("Published domain event: eventType={}, crn={}, detailUrl={}", eventType.type, crn, detailUrl)
    } catch (e: Exception) {
      LOGGER.error("Failed to publish domain event: {}", PiiSanitizer.sanitizeMessage(e.message ?: "Unknown error", crn, null) + " [eventType=${eventType.type}]")
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(DomainEventService::class.java)
  }
}
