package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.offender.NotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.NotificationResults

interface NotificationService {
  fun sendMessage(message: Message, recipient: Contactable, context: NotificationContext): NotificationResults
}
