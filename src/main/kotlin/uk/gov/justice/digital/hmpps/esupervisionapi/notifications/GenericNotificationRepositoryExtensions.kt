package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender

fun GenericNotificationRepository.saveNotifications(
  messageType: NotificationType,
  context: NotificationContext,
  offender: Offender?,
  results: NotificationResults,
) {
  if (results.results.isNotEmpty()) {
    val entities = results.results.map {
      GenericNotification(
        notificationId = it.notificationId,
        messageType = messageType.name,
        reference = context.reference,
        offender = offender,
      )
    }
    this.saveAll(entities)
  }
}
