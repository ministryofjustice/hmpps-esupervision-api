package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.offender.NotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.NotificationResults
import java.util.UUID

data class NotificationInfo(val uuid: UUID, val status: String)

interface NotificationStatusCollection {
  val notifications: List<NotificationInfo>
  val hasNextPage: Boolean
  val previousPageParam: String?
}

interface NotificationService {
  fun sendMessage(message: Message, recipient: Contactable, context: NotificationContext): NotificationResults

  /**
   * @param ref job which sent the notification
   * @param olderThan notification id
   */
  fun notificationStatus(ref: Referencable, olderThan: String? = null): NotificationStatusCollection
}
