package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import java.util.UUID

/**
 * Marker interface; use it to tag classes that are not ready to be processed (e.g., they're missing some values)
 */
interface IPartialEvent

interface IEventBase {
  val checkinId: Long
  val offenderId: Long
  val practitionerId: ExternalUserId
  val checkin: CheckinV2Dto
  val offenderContactPreference: ContactPreference
}

/**
 * Our listeners should be able to process any subclass of this.
 */
sealed interface ICheckinEvent : IEventBase {
  val outboxItemCoords: Pair<OutboxItemType, Long>? get() = null
}

data class CheckinSubmittedEvent(
  override val checkinId: Long,
  override val offenderId: Long,
  override val practitionerId: ExternalUserId,
  override val checkin: CheckinV2Dto,
  override val offenderContactPreference: ContactPreference,
) : ICheckinEvent {
  override val outboxItemCoords = OutboxItemType.CHECKIN_SUBMITTED to checkinId
}

data class CheckinReviewedEvent(
  override val checkinId: Long,
  override val offenderId: Long,
  override val practitionerId: ExternalUserId,
  override val checkin: CheckinV2Dto,
  override val offenderContactPreference: ContactPreference,
) : ICheckinEvent {
  override val outboxItemCoords = OutboxItemType.CHECKIN_REVIEWED to checkinId
}

data class CheckinAnnotatedEvent(
  override val checkinId: Long,
  override val offenderId: Long,
  override val practitionerId: ExternalUserId,
  override val checkin: CheckinV2Dto,
  override val offenderContactPreference: ContactPreference,
  val annotation: Pair<Long, UUID>,
) : ICheckinEvent {
  override val outboxItemCoords = OutboxItemType.CHECKIN_ANNOTATED to annotation.first
}

/**
 * Fully finalised event requires the `outboxItemCoords` to be set.
 */
data class PartialCheckinAnnotatedEvent(
  override val checkinId: Long,
  override val offenderId: Long,
  override val practitionerId: ExternalUserId,
  override val checkin: CheckinV2Dto,
  override val offenderContactPreference: ContactPreference,
) : IEventBase,
  IPartialEvent {
  fun finalise(logEntry: OffenderEventLogV2): CheckinAnnotatedEvent = CheckinAnnotatedEvent(
    checkinId = checkinId,
    offenderId = offenderId,
    checkin = checkin,
    practitionerId = practitionerId,
    offenderContactPreference = offenderContactPreference,
    annotation = Pair(logEntry.id, logEntry.uuid),
  )
}
