package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events

internal const val V2_PREFIX = "esupervision"

enum class DomainEventType(val type: String, val description: String) {
  V2_SETUP_COMPLETED(
    "$V2_PREFIX.setup.completed",
    "An e-Supervision V2 offender setup was completed by practitioner",
  ),
  V2_CHECKIN_CREATED(
    "$V2_PREFIX.check-in.created",
    "An e-Supervision V2 remote check-in was created",
  ),
  V2_CHECKIN_SUBMITTED(
    "$V2_PREFIX.check-in.submitted",
    "An e-Supervision V2 remote check-in was submitted",
  ),
  V2_CHECKIN_REVIEWED(
    "$V2_PREFIX.check-in.reviewed",
    "An e-Supervision V2 remote check-in was reviewed",
  ),
  V2_CHECKIN_EXPIRED(
    "$V2_PREFIX.check-in.expired",
    "An e-Supervision V2 remote check-in was expired",
  ),
}
