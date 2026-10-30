package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PartialCheckinCreatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationService
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.util.UUID

/**
 * ESUP-2223: creating a check-in activates the offender's upcoming (SCHEDULED) check-in for that
 * date, and SCHEDULED check-ins don't count as occupying a date.
 */
class ScheduledCheckinActivationIT : IntegrationTestBase() {

  @Autowired
  private lateinit var checkinCreationService: CheckinCreationService

  @Autowired
  private lateinit var offenderDeactivationService: OffenderDeactivationService

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  private lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired
  private lateinit var transactionTemplate: TransactionTemplate

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  private lateinit var clock: Clock

  @AfterEach
  fun tearDown() {
    jdbcTemplate.update("TRUNCATE TABLE generic_notification_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE event_audit_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE outbox_items RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_checkin_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_v2 RESTART IDENTITY CASCADE")
  }

  @Test
  fun `creating a check-in activates the scheduled check-in for that date, keeping its UUID`() {
    val offender = saveOffender()
    val scheduled = saveCheckin(offender, CheckinStatus.SCHEDULED, clock.today())

    val created = transactionTemplate.execute {
      checkinCreationService.createCheckinForOffender(offender, clock.today(), "PRACT001", contactDetails(offender))
    }!!

    assertThat(created.uuid).isEqualTo(scheduled.uuid)
    val checkins = checkinRepository.findAllByOffenderAndDueDate(offender, clock.today())
    assertThat(checkins).singleElement().satisfies({
      assertThat(it.uuid).isEqualTo(scheduled.uuid)
      assertThat(it.status).isEqualTo(CheckinStatus.CREATED)
      assertThat(it.createdBy).isEqualTo("PRACT001")
    })
    assertThat(outboxItemRepository.findByTypeAndEntityId(OutboxItemType.CHECKIN_CREATED, scheduled.id)).isPresent
  }

  @Test
  fun `an upcoming check-in cancelled after it was read is not activated`() {
    val offender = saveOffender()
    saveCheckin(offender, CheckinStatus.SCHEDULED, clock.today())
    val scheduled = checkinRepository.findScheduledForOffenders(listOf(offender.id), clock.today()).single()
    // check-ins stopped while the creation job was getting contact details
    transactionTemplate.execute { checkinRepository.updateStatusForOffender(offender, CheckinStatus.SCHEDULED, CheckinStatus.CANCELLED) }

    val checkin = checkinCreationService.prepareCheckinForOffender(offender, clock.today(), scheduled)
    val event = PartialCheckinCreatedEvent(
      offenderId = offender.id,
      practitionerId = "SYSTEM",
      checkin = checkin.dto(contactDetails(offender), clock = clock, checkinWindow = Period.ofDays(3)),
      offenderContactPreference = offender.contactPreference,
      currentEvent = 1L,
      checkinMode = offender.mode,
    )
    checkinCreationService.createCheckins(listOf(checkin to event))

    assertThat(checkinRepository.findByUuid(scheduled.uuid).get().status).isEqualTo(CheckinStatus.CANCELLED)
    assertThat(outboxItemRepository.findByTypeAndEntityId(OutboxItemType.CHECKIN_CREATED, scheduled.id)).isEmpty
  }

  @Test
  fun `a scheduled check-in for another date is left alone`() {
    val offender = saveOffender()
    val scheduled = saveCheckin(offender, CheckinStatus.SCHEDULED, clock.today().plusDays(7))

    val created = transactionTemplate.execute {
      checkinCreationService.createCheckinForOffender(offender, clock.today(), "PRACT001", contactDetails(offender))
    }!!

    assertThat(created.uuid).isNotEqualTo(scheduled.uuid)
    assertThat(checkinRepository.findByUuid(scheduled.uuid).get().status).isEqualTo(CheckinStatus.SCHEDULED)
  }

  @Test
  fun `a scheduled check-in doesn't stop the schedule moving`() {
    val offender = saveOffender()
    saveCheckin(offender, CheckinStatus.SCHEDULED, clock.today().plusDays(7))

    val updated = transactionTemplate.execute {
      offenderRepository.maybeUpdate(offender.id, clock.today().plusDays(3), clock.today())
    }

    assertThat(updated).isEqualTo(1)
  }

  @Test
  fun `a cancelled future check-in doesn't stop the schedule moving`() {
    val offender = saveOffender()
    saveCheckin(offender, CheckinStatus.CANCELLED, clock.today().plusDays(7))

    val updated = transactionTemplate.execute {
      offenderRepository.maybeUpdate(offender.id, clock.today().plusDays(3), clock.today())
    }

    assertThat(updated).isEqualTo(1)
  }

  @Test
  fun `deactivation cancels the scheduled check-in`() {
    val offender = saveOffender()
    val scheduled = saveCheckin(offender, CheckinStatus.SCHEDULED, clock.today().plusDays(7))

    offenderDeactivationService.deactivateOffender(offender, "stopped", practitionerMessageFragment = null, contactDetails = contactDetails(offender))

    assertThat(checkinRepository.findByUuid(scheduled.uuid).get().status).isEqualTo(CheckinStatus.CANCELLED)
  }

  @Test
  fun `an offender has at most one scheduled check-in`() {
    val offender = saveOffender()
    saveCheckin(offender, CheckinStatus.SCHEDULED, clock.today().plusDays(7))

    assertThrows<DataIntegrityViolationException> {
      saveCheckin(offender, CheckinStatus.SCHEDULED, clock.today().plusDays(14))
    }
  }

  private fun saveOffender(): Offender = offenderRepository.save(
    offenderTemplate.copy(
      uuid = UUID.randomUUID(),
      crn = "X${(100000..999999).random()}",
      status = OffenderStatus.VERIFIED,
      firstCheckin = clock.today(),
    ).toEntity(),
  )

  private fun saveCheckin(offender: Offender, status: CheckinStatus, dueDate: LocalDate): OffenderCheckin = checkinRepository.save(
    OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = offender,
      status = status,
      dueDate = dueDate,
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
    ),
  )

  private fun contactDetails(offender: Offender) = ContactDetails(
    crn = offender.crn,
    name = Name("John", "Smith"),
    email = "pop@example.com",
    practitioner = PractitionerDetails(name = Name("Jane", "Jones"), email = "practitioner@example.com"),
    events = listOf(Event(number = 1L, mainOffence = CodedDescription("OFF01", "Test"), sentence = null)),
    dateOfBirth = LocalDate.of(1980, 1, 1),
  )
}
