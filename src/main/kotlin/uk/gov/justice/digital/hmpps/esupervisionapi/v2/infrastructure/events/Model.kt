package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events

import java.time.ZonedDateTime

data class AdditionalInformation(val eventNumber: Long? = null)

data class DomainEvent(
  val eventType: String,
  val detailUrl: String,
  val occurredAt: ZonedDateTime,
  val description: String,
  val personReference: PersonReference?,
  val additionalInformation: AdditionalInformation? = null,
)

data class PersonReference(val identifiers: List<PersonIdentifier>) {
  data class PersonIdentifier(val type: String, val value: String)
}
