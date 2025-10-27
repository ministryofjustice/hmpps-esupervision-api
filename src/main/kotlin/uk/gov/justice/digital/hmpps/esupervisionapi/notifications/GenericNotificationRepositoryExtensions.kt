package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationResults

fun GenericNotificationRepository.saveNotifications(messageType: NotificationType, context: NotificationContext, results: NotificationResults) {
  if (results.results.isNotEmpty()) {
    val entities = results.results.map {
      GenericNotification(
        notificationId = it.notificationId,
        messageType = messageType.name,
        reference = context.reference,
      )
    }
    this.saveAll(entities)
  }
}
