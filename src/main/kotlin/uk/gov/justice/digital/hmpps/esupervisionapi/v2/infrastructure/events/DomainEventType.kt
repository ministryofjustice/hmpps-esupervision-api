package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events

internal const val V2_PREFIX = "esupervision"

enum class DomainEventType(
  val type: String,
  val description: String,
  val pathSegment: String,
) {
  V2_SETUP_COMPLETED(
    "$V2_PREFIX.setup.completed",
    "An e-Supervision V2 offender setup was completed by practitioner",
    "setup-completed",
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

  /** In esupervision we talk about "annotating a check-in" rather than "updating it",
   * but external services call it "update". */
  V2_CHECKIN_UPDATED(
    "$V2_PREFIX.check-in.updated",
    "An e-Supervision V2 remote check-in was updated",
    "checkin-updated",
  ),
  ;

  /** Event type name without V2_ prefix (e.g., "SETUP_COMPLETED" instead of "V2_SETUP_COMPLETED") */
  val eventTypeName: String
    get() = name.removePrefix("V2_")

  companion object {
    fun fromPath(path: String): DomainEventType? = entries.find { it.pathSegment == path }
  }
}
