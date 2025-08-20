package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Contactable
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Email
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationMethod
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PhoneNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import java.util.Optional

typealias PractitionerUuid = String

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

@Repository
interface PractitionerRepository : org.springframework.data.jpa.repository.JpaRepository<Practitioner, Long> {
  fun findByEmail(email: String): Optional<Practitioner>
  fun findByUuid(uuid: String): Optional<Practitioner>
}
