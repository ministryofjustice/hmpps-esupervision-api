package uk.gov.justice.digital.hmpps.esupervisionapi.events

import java.time.ZonedDateTime

const val DOMAIN_EVENT_VERSION = 1

interface AdditionalInformation {
  val checkInUrl: String
}

data class DomainEvent(
  val eventType: String,
  val version: Int,
  val detailUrl: String?,
  val occurredAt: ZonedDateTime,
  val description: String,
  val additionalInformation: AdditionalInformation,
  val personReference: PersonReference?,
)

data class PersonReference(val identifiers: List<PersonIdentifier>) {
  data class PersonIdentifier(val type: String, val value: String)
}

data class CheckinAdditionalInformation(
  override val checkInUrl: String,
) : AdditionalInformation
