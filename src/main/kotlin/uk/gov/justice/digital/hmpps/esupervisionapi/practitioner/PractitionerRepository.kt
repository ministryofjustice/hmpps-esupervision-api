package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Contactable
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Email
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationMethod
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException

// Type of identifiers for users within the authentication system
typealias ExternalUserId = String

interface AuthUser {
  fun externalUserId(): ExternalUserId
}

data class NewPractitioner(
  val username: String,
  val name: String,
  val email: String,
) : Contactable,
  AuthUser {
  override fun contactMethods(): Iterable<NotificationMethod> = listOf(Email(this.email))
  override fun externalUserId(): ExternalUserId = username
}

interface NewPractitionerRepository {
  fun findById(id: ExternalUserId): NewPractitioner?

  fun expectById(id: ExternalUserId): NewPractitioner {
    val p = findById(id)
    if (p == null) {
      throw BadArgumentException("Practitioner with external id $id not found")
    } else {
      return p
    }
  }
}
