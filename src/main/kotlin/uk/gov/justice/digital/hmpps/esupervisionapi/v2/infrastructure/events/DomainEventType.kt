package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events

internal const val V2_PREFIX = "esupervision"

enum class DomainEventType(
  val type: String,
  val description: String,
  val pathSegment: String? = null,
) {
  V2_SETUP_COMPLETED(
    "$V2_PREFIX.setup.completed",
    "An e-Supervision V2 offender setup was completed by practitioner",
  ),
  V2_CHECKIN_CREATED(
    "$V2_PREFIX.check-in.created",
    "An e-Supervision V2 remote check-in was created",
    "checkin-created",
  ),
  V2_CHECKIN_SUBMITTED(
    "$V2_PREFIX.check-in.received",
    "An e-Supervision V2 remote check-in was received",
    "checkin-submitted",
  ),
  V2_CHECKIN_REVIEWED(
    "$V2_PREFIX.check-in.reviewed",
    "An e-Supervision V2 remote check-in was reviewed",
    "checkin-reviewed",
  ),
  V2_CHECKIN_EXPIRED(
    "$V2_PREFIX.check-in.expired",
    "An e-Supervision V2 remote check-in was expired",
    "checkin-expired",
  ),

  V2_SETUP_REMOVED(
    "$V2_PREFIX.setup.removed",
    "An e-Supervision V2 offender's online check-ins were stopped",
  ),

  // ESUP-2223: lifecycle of an upcoming check-in. Published via checkin_delius_event_v2;
  // the detail URL carries that row's event_id, not the check-in UUID.
  V2_CHECKIN_SCHEDULED(
    "$V2_PREFIX.check-in.scheduled",
    "An e-Supervision V2 remote check-in was scheduled",
    "checkin-scheduled",
  ),

  /** Moves the date of a scheduled check-in. External services call it "update", like annotations. */
  V2_CHECKIN_RESCHEDULED(
    "$V2_PREFIX.check-in.updated",
    "An e-Supervision V2 remote check-in was rescheduled",
    "checkin-rescheduled",
  ),
  V2_CHECKIN_CANCELLED(
    "$V2_PREFIX.check-in.cancelled",
    "An e-Supervision V2 remote check-in was cancelled",
    "checkin-cancelled",
  ),
  V2_CHECKIN_LINK_DELIVERED(
    "$V2_PREFIX.check-in.link.delivered",
    "An e-Supervision V2 remote check-in link was sent",
    "checkin-link-delivered",
  ),
  V2_CHECKIN_LINK_NOT_DELIVERED(
    "$V2_PREFIX.check-in.link.not_delivered",
    "An e-Supervision V2 remote check-in link could not be sent",
    "checkin-link-not-delivered",
  ),

  /** In esupervision we talk about "annotating a check-in" rather than "updating it",
   * but external services call it "update". */
  V2_CHECKIN_ANNOTATED(
    "$V2_PREFIX.check-in.updated",
    "An e-Supervision V2 remote check-in was annotated",
    "checkin-annotated",
  ),
  ;

  /** Event type name without V2_ prefix (e.g., "SETUP_COMPLETED" instead of "V2_SETUP_COMPLETED") */
  val eventTypeName: String
    get() = name.removePrefix("V2_")

  companion object {
    fun fromPath(path: String): DomainEventType? = entries.find { it.pathSegment == path }
  }
}
