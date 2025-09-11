package uk.gov.justice.digital.hmpps.esupervisionapi.events

import java.time.ZonedDateTime

const val DOMAIN_EVENT_VERSION = 1

interface AdditionalInformation {
  val checkInUrl: String
}

interface DomainEvent {
  val eventType: String
  val version: Int
  val detailUrl: String?
  val occurredAt: ZonedDateTime
  val description: String
  val additionalInformation: AdditionalInformation
  val personReference: PersonReference?
}

data class PersonReference(val identifiers: List<PersonIdentifier>) {
  data class PersonIdentifier(val type: String, val value: String)
}

data class CheckinAdditionalInformation(
  override val checkInUrl: String,
) : AdditionalInformation

data class HmppsDomainEvent(
  override val eventType: String,
  override val version: Int,
  override val detailUrl: String?,
  override val occurredAt: ZonedDateTime,
  override val description: String,
  override val additionalInformation: AdditionalInformation,
  override val personReference: PersonReference?,
) : DomainEvent
