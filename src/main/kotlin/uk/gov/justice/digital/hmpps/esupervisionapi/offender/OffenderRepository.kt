package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Contactable
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Email
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationMethod
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PhoneNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.ResourceLocator
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

enum class OffenderStatus {
  // record has been created
  INITIAL,

  // practitioner approved info & id information submitted by offender
  VERIFIED,

  // practitioner (or some kind of admin) disabled the account, no further notifications will be sent
  INACTIVE,
}

@Entity
@Table(name = "offender")
open class Offender(
  @Column(unique = true, nullable = false)
  open var uuid: UUID,

  @Column("first_name", nullable = false)
  open var firstName: String,

  @Column("last_name", nullable = false)
  open var lastName: String,

  @Column("date_of_birth")
  open var dateOfBirth: LocalDate?,

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  // @Version
  open var status: OffenderStatus = OffenderStatus.INITIAL,

  @Column(name = "created_at", nullable = false)
  open var createdAt: Instant,

  @Column(name = "updated_at", nullable = false)
  open var updatedAt: Instant,

  @Column(unique = true, nullable = true)
  open var email: String? = null,

  @Column(name = "phone_number", nullable = true, unique = true)
  open var phoneNumber: String? = null,

  @ManyToOne(cascade = [CascadeType.DETACH], fetch = FetchType.LAZY)
  @JoinColumn(name = "practitioner_id", referencedColumnName = "id", nullable = false)
  open var practitioner: Practitioner,
) : AEntity(),
  Contactable {
  fun dto(resourceLocator: ResourceLocator): OffenderDto = OffenderDto(
    uuid = uuid,
    firstName = firstName,
    lastName = lastName,
    dateOfBirth = dateOfBirth,
    status = status,
    email = email,
    phoneNumber = phoneNumber,
    createdAt = createdAt,
    photoUrl = resourceLocator.getOffenderPhoto(this),
  )

  override fun contactMethods(): Iterable<NotificationMethod> {
    val methods = mutableListOf<NotificationMethod>()
    this.email?.let { methods.add(Email(it)) }
    this.phoneNumber?.let { methods.add(PhoneNumber(it)) }
    return methods
  }
}

@Repository
interface OffenderRepository : org.springframework.data.jpa.repository.JpaRepository<Offender, Long> {
  fun findByEmail(email: String): Optional<Offender>
  fun findByPhoneNumber(phoneNumber: String): Optional<Offender>
  fun findByUuid(uuid: UUID): Optional<Offender>
}

/**
 * When a practitioner adds an offender, a record for the setup process is created.
 * This give us an UUID to use until the actual offender record can be created.
 */
@Entity
@Table(name = "offender_setup")
open class OffenderSetup(
  @Column(unique = true, nullable = false)
  open var uuid: UUID,

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn("offender_id", "id")
  open var offender: Offender,

  @ManyToOne
  @JoinColumn(name = "practitioner_id", referencedColumnName = "id")
  open var practitioner: Practitioner,

  @Column("created_at")
  open var createdAt: Instant,
) : AEntity() {
  fun dto(): OffenderSetupDto = OffenderSetupDto(
    uuid = uuid,
    practitioner = practitioner.uuid,
    offender = offender.uuid,
    createdAt = createdAt,
  )
}

@Repository
interface OffenderSetupRepository : org.springframework.data.jpa.repository.JpaRepository<OffenderSetup, Long> {
  fun findByUuid(uuid: UUID): Optional<OffenderSetup>
}
