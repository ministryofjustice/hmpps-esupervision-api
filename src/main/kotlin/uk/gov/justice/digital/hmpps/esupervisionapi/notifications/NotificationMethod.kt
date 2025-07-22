package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

enum class NotificationMethodKey {
  PHONE,
  EMAIL,
}

sealed class NotificationMethod {
  abstract val method: NotificationMethodKey
}

data class PhoneNumber(val phoneNumber: String) : NotificationMethod() {
  override val method = NotificationMethodKey.PHONE
}
data class Email(val email: String) : NotificationMethod() {
  override val method = NotificationMethodKey.EMAIL
}
