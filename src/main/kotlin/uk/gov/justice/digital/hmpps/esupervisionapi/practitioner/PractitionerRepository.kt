package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Contactable
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Email
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationMethod
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PhoneNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException

typealias PractitionerUuid = String

// Type of identifiers for users within the authentication system
typealias ExternalUserId = String

@Entity
@Table(name = "practitioner")
open class Practitioner(
  @Column(unique = true, nullable = false)
  open var uuid: PractitionerUuid,
  @Column("first_name")
  open var firstName: String,
  @Column("last_name")
  open var lastName: String,
  @Column(unique = true, nullable = false)
  open var email: String,
  @Column("phone_number")
  open var phoneNumber: String? = null,
  open var roles: List<String> = listOf(),
) : AEntity(),
  Contactable {
  fun dto(): PractitionerDto = PractitionerDto(
    uuid = uuid,
    firstName = firstName,
    lastName = lastName,
    email = email,
    phoneNumber = phoneNumber,
    roles = roles,
  )

  override fun contactMethods(): Iterable<NotificationMethod> {
    val methods = mutableListOf<NotificationMethod>(Email(this.email))
    this.phoneNumber?.let { methods.add(PhoneNumber(it)) }
    return methods
  }

  companion object {}
}

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
