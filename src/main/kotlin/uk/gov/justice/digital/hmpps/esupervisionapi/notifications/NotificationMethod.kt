package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

sealed class NotificationMethod

class PhoneNumber(val phoneNumber: String) : NotificationMethod()
class Email(val email: String) : NotificationMethod()
