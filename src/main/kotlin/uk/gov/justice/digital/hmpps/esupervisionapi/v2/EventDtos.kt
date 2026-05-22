package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId

sealed interface ICheckinEvent {
  val checkinId: Long
  val offenderId: Long
  val practitionerId: ExternalUserId
  val checkin: CheckinV2Dto
  val offenderContactPreference: ContactPreference
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
