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
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Email
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationMethod
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PhoneNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/*
 * o -(Practitioner creates an invite)-> INITIAL -(Offender responds to invite)-> WAITING
 * WAITING -(Practitioner verifies offender)-> VERIFIED
 * WAITING -(Practitioner rejects offender)-> REJECTED
 * VERIFIED -(Practitioner disabled by Practitioner/System)-> INACTIVE
 */

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

  @ManyToOne(cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
  @JoinColumn(name = "practitioner_id", referencedColumnName = "id", nullable = false)
  open var practitioner: Practitioner,
) : AEntity() {
  fun dto(): OffenderDto = OffenderDto(
    uuid = uuid,
    firstName = firstName,
    lastName = lastName,
    dateOfBirth = dateOfBirth,
    status = status,
    email = email,
    phoneNumber = phoneNumber,
    createdAt = createdAt,
    photoUrl = null,
  )
}

enum class OffenderInviteStatus {
  // the record has been created, invite possibly scheduled
  CREATED,

  // an invite has been sent
  SENT,

  // offender responded to an invite
  RESPONDED,

  // practitioner approved the offender's response
  APPROVED,

  // practitioner rejected the offender's response
  REJECTED,

  // the invite expired
  EXPIRED,
}

/**
 * Represents an intent to invite an offender to the system.
 *
 */
@Entity
@Table(name = "offender_invite")
open class OffenderInvite(
  @Column(unique = true, nullable = false)
  open var uuid: UUID,
  @Column(name = "created_at", nullable = false)
  open var createdAt: Instant,
  @Column(name = "updated_at", nullable = false)
  open var updatedAt: Instant,
  @Column(name = "expires_on", nullable = false)
  open var expiresOn: Instant?,
  @Column(name = "first_name", nullable = false)
  open var firstName: String,
  @Column(name = "last_name", nullable = false)
  open var lastName: String,
  @Column(name = "date_of_birth")
  open var dateOfBirth: LocalDate,
  @ManyToOne(cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
  @JoinColumn(name = "practitioner_id", referencedColumnName = "id", nullable = false)
  open var practitioner: Practitioner,
  @Column("phone_number", nullable = true)
  open var phoneNumber: String? = null,
  open var email: String? = null,
  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  // @Version
  open var status: OffenderInviteStatus = OffenderInviteStatus.CREATED,
) : AEntity() {
  fun notificationMethods(): List<NotificationMethod> {
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

  @ManyToOne(fetch = FetchType.LAZY)
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
