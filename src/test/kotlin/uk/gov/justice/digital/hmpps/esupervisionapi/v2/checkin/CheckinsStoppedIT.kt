package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
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
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventNotes
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationService
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * ESUP-2223: stopping check-ins tells NDelius the upcoming and open check-ins are cancelled. The
 * feature flag is switched on per test, so this shares the default test context.
 */
class CheckinsStoppedIT : IntegrationTestBase() {

  @Autowired
  private lateinit var offenderDeactivationService: OffenderDeactivationService

  @Autowired
  private lateinit var upcomingCheckinService: UpcomingCheckinService

  @Autowired
  private lateinit var checkinDeliusEventService: CheckinDeliusEventService

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  private lateinit var deliusEventRepository: CheckinDeliusEventRepository

  @Autowired
  private lateinit var appConfig: AppConfig

  @Autowired
  private lateinit var transactionTemplate: TransactionTemplate

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  private lateinit var clock: Clock

  private lateinit var featuresBefore: Set<Feature>

  private val today get() = clock.today()

  @BeforeEach
  fun enableFeature() {
    featuresBefore = appConfig.enabledFeatures
    setFeatures(featuresBefore + Feature.ESUP_2223)
  }

  @AfterEach
  fun tearDown() {
    setFeatures(featuresBefore)
    jdbcTemplate.update("TRUNCATE TABLE checkin_delius_event_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE event_audit_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE generic_notification_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE outbox_items RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_checkin_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_v2 RESTART IDENTITY CASCADE")
  }

  @Test
  fun `stopping check-ins cancels the upcoming check-in in NDelius`() {
    val offender = saveOffender(currentEvent = 4)
    upcomingCheckinService.scheduleNext(offender.id)
    val scheduled = checkinRepository.findAllByOffenderAndStatus(offender, CheckinStatus.SCHEDULED).single()

    stop(offender)

    assertThat(checkinRepository.findByUuid(scheduled.uuid).get().status).isEqualTo(CheckinStatus.CANCELLED)
    val cancelled = deliusEventRepository.findAllByCheckinUuidOrderById(scheduled.uuid).last()
    assertThat(cancelled.eventType).isEqualTo(DomainEventType.V2_CHECKIN_CANCELLED)
    assertThat(cancelled.notes).isEqualTo("Reason: ${OffenderPersistenceService.CHECKINS_STOPPED_REASON}")
    assertThat(cancelled.eventNumber).isNull()
  }

  @Test
  fun `stopping check-ins cancels the open check-in in NDelius`() {
    val offender = saveOffender()
    val open = saveCheckin(offender, CheckinStatus.CREATED)
    transactionTemplate.executeWithoutResult {
      checkinDeliusEventService.record(DomainEventType.V2_CHECKIN_SCHEDULED, open, CheckinDeliusEventNotes.scheduled(today), eventNumber = null, date = today)
    }

    stop(offender)

    assertThat(deliusEventRepository.findAllByCheckinUuidOrderById(open.uuid).map { it.eventType })
      .containsExactly(DomainEventType.V2_CHECKIN_SCHEDULED, DomainEventType.V2_CHECKIN_CANCELLED)
  }

  @Test
  fun `a check-in NDelius doesn't have is cancelled without telling it`() {
    val offender = saveOffender()
    val open = saveCheckin(offender, CheckinStatus.CREATED)

    stop(offender)

    assertThat(checkinRepository.findByUuid(open.uuid).get().status).isEqualTo(CheckinStatus.CANCELLED)
    assertThat(deliusEventRepository.findAll()).isEmpty()
  }

  @Test
  fun `with the feature off, NDelius isn't told`() {
    val offender = saveOffender()
    upcomingCheckinService.scheduleNext(offender.id)
    val scheduled = checkinRepository.findAllByOffenderAndStatus(offender, CheckinStatus.SCHEDULED).single()
    setFeatures(featuresBefore - Feature.ESUP_2223)

    stop(offender)

    assertThat(checkinRepository.findByUuid(scheduled.uuid).get().status).isEqualTo(CheckinStatus.CANCELLED)
    assertThat(deliusEventRepository.findAll().map { it.eventType }).containsExactly(DomainEventType.V2_CHECKIN_SCHEDULED)
  }

  private fun stop(offender: Offender) {
    offenderDeactivationService.deactivateOffender(offender, "stopped", practitionerMessageFragment = null, contactDetails = contactDetails(offender))
  }

  private fun setFeatures(features: Set<Feature>) = ReflectionTestUtils.setField(appConfig, "enabledFeatures", features)

  private fun saveOffender(currentEvent: Long? = null): Offender = offenderRepository.save(
    offenderTemplate.copy(
      uuid = UUID.randomUUID(),
      crn = "X${(100000..999999).random()}",
      status = OffenderStatus.VERIFIED,
      firstCheckin = today.plusDays(3),
      checkinInterval = CheckinInterval.WEEKLY,
    ).toEntity().also { it.currentEvent = currentEvent },
  )

  private fun saveCheckin(offender: Offender, status: CheckinStatus): OffenderCheckin = checkinRepository.save(
    OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = offender,
      status = status,
      dueDate = today,
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
