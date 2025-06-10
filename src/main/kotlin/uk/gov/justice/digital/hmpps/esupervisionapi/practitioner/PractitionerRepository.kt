package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

@Entity
@Table(name = "practitioner")
open class Practitioner(
  @Column(unique = true, nullable = false)
  open var uuid: UUID,
  @Column("first_name")
  open var firstName: String,
  @Column("last_name")
  open var lastName: String,
  @Column(unique = true, nullable = false)
  open var email: String,
  @Column("phone_number")
  open var phoneNumber: String? = null,
  open var roles: List<String> = listOf()
): AEntity()
//{
//  override fun toString(): String {
//    return "Practitioner(id=$uuid, firstName='$firstName', lastName='$lastName', email='$email', phoneNumber=$phoneNumber)"
//  }
//}

@Repository
interface PractitionerRepository : org.springframework.data.jpa.repository.JpaRepository<Practitioner, Long> {
  fun findByEmail(email: String): Optional<Practitioner>
  fun findByUuid(uuid: UUID): Optional<Practitioner>
}
