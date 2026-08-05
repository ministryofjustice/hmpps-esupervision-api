package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import java.util.UUID

/**
 * Marker interface; use it to tag classes that are not ready to be processed (e.g., they're missing some values)
 */
interface IPartialEvent

interface IOffenderEventBase {
  val offenderId: Long
  val offender: OffenderDto
}

/**
 * Our listeners should be able to process any subclass of this.
 */
sealed interface IOffenderEvent : IOffenderEventBase {
  val outboxItemCoords: Pair<OutboxItemType, Long>? get() = null
}

data class OffenderDeactivatedEvent(
  override val offenderId: Long,
  override val offender: OffenderDto,
  val auditEventType: OffenderAuditEventType,
  val setup: Pair<Long, UUID>?,
  /**
   * See [uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.activeEventNumber]
   */
  val activeEventNumber: Long?,
  val reason: String? = null,
  val sensitive: Boolean = false,
) : IOffenderEvent {
  override val outboxItemCoords = setup?.let { OutboxItemType.OFFENDER_DEACTIVATED to setup.first }
}

data class PartialOffenderReactivatedEvent(
  override val offenderId: Long,
  override val offender: OffenderDto,
  override val currentEvent: Long?,
  val reason: String,
) : IOffenderEventBase,
  IPartialEvent,
  ActiveEvent {
  fun finalise(setup: OffenderSetup, offender: Offender): OffenderReactivatedEvent = OffenderReactivatedEvent(
    offenderId = offenderId,
    offender = offender.dto(this.offender.personalDetails),
    currentEvent = this.currentEvent,
    setup = setup.let { SetupInfo.from(it) },
    reason = this.reason,
  )
}

data class SetupInfo private constructor(
  val primaryKey: Long,
  val setupId: UUID,
) {
  companion object {
    fun from(setup: OffenderSetup) = SetupInfo(primaryKey = setup.id, setupId = setup.setupId())
  }
}

data class OffenderReactivatedEvent(
  override val offenderId: Long,
  override val offender: OffenderDto,
  override val currentEvent: Long?,
  val setup: SetupInfo,
  val reason: String,
) : IOffenderEvent,
  ActiveEvent {
  override val outboxItemCoords = Pair(OutboxItemType.OFFENDER_REACTIVATED, setup.primaryKey)
}

interface ICheckinEventBase {
  val checkinId: Long
  val offenderId: Long
  val practitionerId: ExternalUserId
  val checkin: CheckinDto
  val offenderContactPreference: ContactPreference
}

/**
 * Our listeners should be able to process any subclass of this.
 */
sealed interface ICheckinEvent : ICheckinEventBase {
  val outboxItemCoords: Pair<OutboxItemType, Long>? get() = null
}

data class CheckinCreatedEvent(
  override val checkinId: Long,
  override val offenderId: Long,
  override val practitionerId: ExternalUserId,
  override val checkin: CheckinDto,
  override val offenderContactPreference: ContactPreference,
  override val currentEvent: Long?,
) : ICheckinEvent,
  ActiveEvent {
  override val outboxItemCoords = OutboxItemType.CHECKIN_CREATED to checkinId
}

data class CheckinSubmittedEvent(
  override val checkinId: Long,
  override val offenderId: Long,
  override val practitionerId: ExternalUserId,
  override val checkin: CheckinDto,
  override val offenderContactPreference: ContactPreference,
) : ICheckinEvent {
  override val outboxItemCoords = OutboxItemType.CHECKIN_SUBMITTED to checkinId
}

data class CheckinReviewedEvent(
  override val checkinId: Long,
  override val offenderId: Long,
  override val practitionerId: ExternalUserId,
  override val checkin: CheckinDto,
  override val offenderContactPreference: ContactPreference,
) : ICheckinEvent {
  override val outboxItemCoords = OutboxItemType.CHECKIN_REVIEWED to checkinId
}

data class CheckinAnnotatedEvent(
  override val checkinId: Long,
  override val offenderId: Long,
  override val practitionerId: ExternalUserId,
  override val checkin: CheckinDto,
  override val offenderContactPreference: ContactPreference,
  val annotation: Pair<Long, UUID>,
) : ICheckinEvent {
  override val outboxItemCoords = OutboxItemType.CHECKIN_ANNOTATED to annotation.first
}

/**
 * Finalised event requires the checkin ID to be set, which we only get after DB insert is done.
 */
data class PartialCheckinCreatedEvent(
  override val checkinId: Long = -1,
  override val offenderId: Long,
  override val practitionerId: ExternalUserId,
  override val checkin: CheckinDto,
  override val offenderContactPreference: ContactPreference,
  override val currentEvent: Long?,
) : ICheckinEventBase,
  IPartialEvent,
  ActiveEvent {
  fun finalise(checkin: OffenderCheckin): CheckinCreatedEvent {
    require(checkin.id != 0L) { "Checkin ID must be set after DB insert" }
    return CheckinCreatedEvent(
      checkinId = checkin.id,
      offenderId = offenderId,
      practitionerId = practitionerId,
      checkin = this.checkin,
      offenderContactPreference = offenderContactPreference,
      currentEvent = currentEvent,
    )
  }
}

/**
 * Fully finalised event requires the `outboxItemCoords` to be set.
 */
data class PartialCheckinAnnotatedEvent(
  override val checkinId: Long,
  override val offenderId: Long,
  override val practitionerId: ExternalUserId,
  override val checkin: CheckinDto,
  override val offenderContactPreference: ContactPreference,
) : ICheckinEventBase,
  IPartialEvent {
  fun finalise(logEntry: OffenderEventLog): CheckinAnnotatedEvent = CheckinAnnotatedEvent(
    checkinId = checkinId,
    offenderId = offenderId,
    checkin = checkin,
    practitionerId = practitionerId,
    offenderContactPreference = offenderContactPreference,
    annotation = Pair(logEntry.id, logEntry.uuid),
  )
}
