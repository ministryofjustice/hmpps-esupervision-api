package uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.DomainEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.AdditionalInformation
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

val DELIUS_ZONE: ZoneId = ZoneId.of("Europe/London")

/**
 * Check-ins only have a due date. NDelius needs a date-time for the contact, so we use the time
 * the check-in creation job sends the links out.
 */
val CHECKIN_TIME_OF_DAY: LocalTime = LocalTime.of(9, 0)

fun checkinDateTime(dueDate: LocalDate): ZonedDateTime = dueDate.atTime(CHECKIN_TIME_OF_DAY).atZone(DELIUS_ZONE)

@Service
class CheckinDeliusEventService(
  private val repository: CheckinDeliusEventRepository,
  private val domainEventService: DomainEventService,
  private val clock: Clock,
  private val appConfig: AppConfig,
) {

  fun enabled() = appConfig.enabledFeatures.contains(Feature.ESUP_2223)

  /**
   * Record a lifecycle event for [checkin], to be published after the surrounding transaction
   * commits. Every event type except rescheduling is recorded at most once per check-in; a repeat
   * is ignored.
   *
   * @param date the check-in date, for scheduled and rescheduled events
   * @return true if recorded, false if the check-in already had an event of that type
   */
  @Transactional(propagation = Propagation.MANDATORY)
  fun record(
    eventType: DomainEventType,
    checkin: OffenderCheckin,
    notes: String,
    eventNumber: Long?,
    date: LocalDate? = null,
  ): Boolean {
    require(eventType in CHECKIN_DELIUS_EVENT_TYPES) { "$eventType is not a check-in lifecycle event" }
    val datedEvent = eventType == DomainEventType.V2_CHECKIN_SCHEDULED || eventType == DomainEventType.V2_CHECKIN_RESCHEDULED
    require(datedEvent == (date != null)) { "$eventType ${if (datedEvent) "requires" else "must not have"} a date" }

    val eventId = UUID.randomUUID()
    return insert(
      CheckinDeliusEvent(
        eventId = eventId,
        eventType = eventType,
        detailId = eventId,
        checkinUuid = checkin.uuid,
        crn = checkin.offender.crn,
        eventNumber = eventNumber,
        dateTime = date?.let { checkinDateTime(it).toInstant() },
        notes = notes,
        occurredAt = clock.instant(),
      ),
    )
  }

  /**
   * Record one of the check-in events that existed before ESUP-2223 (received, expired, reviewed,
   * updated), so it is published in order with the check-in's lifecycle events. Its detail is
   * built from the check-in, as before. Callers use this instead of publishing directly when
   * [enabled].
   *
   * @param detailId the id the event's detail endpoint expects: the check-in UUID, or for an annotation its log entry UUID
   */
  @Transactional(propagation = Propagation.MANDATORY)
  fun recordCheckinUpdate(eventType: DomainEventType, checkin: OffenderCheckin, detailId: UUID = checkin.uuid): Boolean {
    require(eventType in CHECKIN_UPDATE_EVENT_TYPES) { "$eventType is not a check-in update event" }
    return insert(
      CheckinDeliusEvent(
        eventId = UUID.randomUUID(),
        eventType = eventType,
        detailId = detailId,
        checkinUuid = checkin.uuid,
        crn = checkin.offender.crn,
        // as when published directly: NDelius matches the contact by check-in
        eventNumber = null,
        dateTime = null,
        notes = null,
        occurredAt = clock.instant(),
      ),
    )
  }

  private fun insert(event: CheckinDeliusEvent): Boolean {
    val inserted = repository.insertUnlessDuplicate(event) == 1
    if (inserted) {
      LOGGER.info("Recorded {} event {} for checkin={}", event.eventType.eventTypeName, event.eventId, event.checkinUuid)
    } else {
      LOGGER.warn("Ignoring duplicate {} event for checkin={}", event.eventType.eventTypeName, event.checkinUuid)
    }
    return inserted
  }

  /**
   * Publish the events that are next in line for their check-in. Stops early after a few failures
   * in a row: the topic is probably unavailable, and each failed publish can take several seconds
   * of SDK retries, which could otherwise keep the job running past its lock.
   * @return the number of events published
   */
  fun publishPending(batchSize: Int, minGap: Duration): Int {
    val events = repository.findPublishable(publishedBefore = clock.instant().minus(minGap), limit = batchSize)
    var published = 0
    var failuresInARow = 0
    for ((index, event) in events.withIndex()) {
      if (failuresInARow >= MAX_FAILURES_IN_A_ROW) {
        LOGGER.warn("Stopping after {} failed publishes in a row, {} events left for the next run", failuresInARow, events.size - index)
        break
      }
      try {
        domainEventService.publishDomainEventOrThrow(
          eventType = event.eventType,
          uuid = event.detailId,
          crn = event.crn,
          description = event.eventType.description,
          occurredAt = event.occurredAt.atZone(DELIUS_ZONE),
          additionalInformation = AdditionalInformation(eventNumber = event.eventNumber),
        )
        repository.markPublished(event.id, clock.instant())
        published++
        failuresInARow = 0
      } catch (e: Exception) {
        failuresInARow++
        val message = PiiSanitizer.sanitizeMessage(e.message ?: e::class.simpleName ?: "Unknown error")
        val attempt = event.publishAttempts + 1
        if (attempt == ALERT_AFTER_ATTEMPTS) {
          // the check-in's later events are held back until this one is published
          LOGGER.error("Failed to publish {} event {} for checkin={} after {} attempts: {}", event.eventType.eventTypeName, event.eventId, event.checkinUuid, attempt, message)
        } else {
          LOGGER.warn("Failed to publish {} event {} for checkin={}, attempt {}: {}", event.eventType.eventTypeName, event.eventId, event.checkinUuid, attempt, message)
        }
        repository.markFailed(event.id, message)
      }
    }
    return published
  }

  companion object {
    private val LOGGER = logger<CheckinDeliusEventService>()
    const val MAX_FAILURES_IN_A_ROW = 3
    const val ALERT_AFTER_ATTEMPTS = 20
  }
}
