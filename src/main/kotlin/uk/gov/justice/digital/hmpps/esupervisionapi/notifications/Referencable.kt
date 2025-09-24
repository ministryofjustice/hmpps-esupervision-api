package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

/**
 * A reference that we can use to look up notifications (in bulk) via GOV.UK Notify API.
 */
interface Referencable {
  val reference: String
}
