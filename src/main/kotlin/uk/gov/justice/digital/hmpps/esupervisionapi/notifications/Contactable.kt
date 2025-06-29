package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

interface Contactable {
  fun contactMethods(): Iterable<NotificationMethod>
}
