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
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Contactable
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Email
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationMethod
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.PhoneNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.AEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.ResourceLocator
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Optional
import java.util.UUID
import java.util.stream.Stream

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

  /**
   * Marks the current schedule's date of first checkin. The following checkin
   * due dates will be calculated based on this property and `checkinInterval`
   *
   * When the schedule needs to change, this date will be updated, effectively
   * marking the start of the new schedule. No checkins for the "old" schedule
   * will be created.
   */
  @Column("first_checkin", nullable = true)
  open val firstCheckin: ZonedDateTime? = null,

  /**
   * Used for scheduling
   */
  @Column("zone_id", nullable = false)
  open val zoneId: ZoneId,

  @Column("checkin_interval", nullable = false)
  open val checkinInterval: Duration,

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
    firstCheckin = firstCheckin?.toLocalDate(),
    checkinInterval = CheckinInterval.fromDuration(checkinInterval),
    zoneId = zoneId,
  )

  override fun contactMethods(): Iterable<NotificationMethod> {
    val methods = mutableListOf<NotificationMethod>()
    this.email?.let { methods.add(Email(it)) }
    this.phoneNumber?.let { methods.add(PhoneNumber(it)) }
    return methods
  }

  companion object {}
}

@Repository
interface OffenderRepository : org.springframework.data.jpa.repository.JpaRepository<Offender, Long> {
  fun findByEmail(email: String): Optional<Offender>
  fun findByPhoneNumber(phoneNumber: String): Optional<Offender>
  fun findByUuid(uuid: UUID): Optional<Offender>
  fun findAllByPractitioner(practitioner: Practitioner, pageable: Pageable): Page<Offender>

  // NOTE(rosado): the below doesn't work on H2
//  @Query("""
//    select o from Offender o
//    where
//        o.status = :status
//        and  o.firstCheckin is not null
//        and o.checkinInterval is not null
//        and function('MOD',
//            function('AGE_IN_DAYS', :timestamp, o.firstCheckin),
//            function('EXTRACT_DAYS', o.checkinInterval)) = 0
//        and not exists (select 1 from OffenderCheckin c where c.dueDate = :timestamp)
//        """)

  @Query(
    """
    select o from Offender o
    where 
        o.status = 'VERIFIED'
        and  o.firstCheckin is not null
        and o.checkinInterval is not null
        and not exists (select 1 from OffenderCheckin c
                        where c.offender = o 
                        and :lowerBoundInclusive <= c.dueDate and c.dueDate < :upperBoundExclusive
                        and c.status = 'CREATED')
        """,
  )
  fun findAllCheckinNotificationCandidates(lowerBoundInclusive: ZonedDateTime, upperBoundExclusive: ZonedDateTime): Stream<Offender>
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
