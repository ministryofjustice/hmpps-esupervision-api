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
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
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

  // offender responded to invite, but has not been approved by practitioner yet
  WAITING,

  // practitioner approved info & id information submitted by offender
  VERIFIED,

  // practitioner rejected info & id information submitted by offender
  REJECTED,

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
  open var dateOfBirth: Instant?,
  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  open var status: OffenderStatus = OffenderStatus.INITIAL,
  @Column(name = "created_at", nullable = false)
  open var createdAt: Instant,
  open var email: String? = null,
  @Column(name = "phone_number")
  open var phoneNumber: String? = null,
) : AEntity()

enum class OffenderInviteStatus {
  // the record has been created, invite possibly scheduled
  CREATED,

  // an invite has been sent
  SENT,

  // offender responded to an invite
  RESPONDED,

  // practitioner approved the offender's response
  APPROVED,

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
  open var status: OffenderInviteStatus = OffenderInviteStatus.CREATED,
  // TODO(rosado): photo url
) : AEntity()

@Repository
interface OffenderRepository : org.springframework.data.jpa.repository.JpaRepository<Offender, Long> {
  fun findByEmail(email: String): Optional<Offender>
  fun findByPhoneNumber(phoneNumber: String): Optional<Offender>
  fun findByUuid(uuid: UUID): Optional<Offender>

  @Query(
    """
    select o from Offender o 
        where o.status = 'VERIFIED'
            and ((o.email is not null and o.email in :emails)
                 or (o.phoneNumber is not null and o.phoneNumber in :phoneNumbers))
  """,
  )
  fun findWithMatchingContactInfo(emails: Iterable<String>, phoneNumbers: Iterable<String>): List<Offender>
}

@Repository
interface OffenderInviteRepository : org.springframework.data.jpa.repository.JpaRepository<OffenderInvite, Long> {
  fun findByUuid(uuid: UUID): Optional<OffenderInvite>
  fun findByPractitioner(practitioner: Practitioner): List<OffenderInvite>

  /**
   * Returns invites with matching contact info and status
   * indicating that the invitation process is currently active.
   *
   * This query can be used to detect if we already invited someone with
   * given email/phone number.
   */
  @Query(
    """
    select oi from OffenderInvite oi
    where oi.status not in ('EXPIRED', 'APPROVED')
          and oi.uuid not in :uuids
          and ((oi.email is not null and oi.email in :emails)
               or (oi.phoneNumber is not null and oi.phoneNumber in :phoneNumbers))
  """,
  )
  fun findWithMatchingContactInfo(
    uuids: Iterable<UUID>,
    emails: Iterable<String>,
    phoneNumbers: Iterable<String>,
  ): List<OffenderInvite>
}
