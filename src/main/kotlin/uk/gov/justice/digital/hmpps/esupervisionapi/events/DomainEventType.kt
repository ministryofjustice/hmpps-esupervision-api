package uk.gov.justice.digital.hmpps.esupervisionapi.events

internal const val PREFIX = "esupervision.check-in"

enum class DomainEventType(val type: String, val description: String) {
  CHECKIN_EXPIRED("$PREFIX.expired", "An e-Supervision remote check-in was expired"),
  CHECKIN_RECEIVED("$PREFIX.received", "An e-Supervision remote check-in was received"),
}
