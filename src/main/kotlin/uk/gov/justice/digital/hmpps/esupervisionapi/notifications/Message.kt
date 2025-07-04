package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig

interface Message {
  fun personalisationData(appConfig: AppConfig): Map<String, String>
  val messageType: NotificationType
}
