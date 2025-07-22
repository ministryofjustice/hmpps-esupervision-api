package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.offender.NotificationResults
import java.util.UUID

data class NotificationReference(
  val method: NotificationMethodKey,
  val notificationId: UUID,
)

interface NotificationService {
  fun sendMessage(message: Message, recipient: Contactable): NotificationResults
}
