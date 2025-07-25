package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

sealed class NotificationMethod

data class PhoneNumber(val phoneNumber: String) : NotificationMethod()
data class Email(val email: String) : NotificationMethod()
