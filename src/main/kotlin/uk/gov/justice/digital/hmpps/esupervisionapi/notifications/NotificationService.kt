package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

interface NotificationService {
  fun sendMessage(message: Message, recipient: Contactable)
}
