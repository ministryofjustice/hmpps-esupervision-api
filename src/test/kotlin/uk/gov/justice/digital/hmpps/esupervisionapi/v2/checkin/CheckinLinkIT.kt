package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinLinkStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PersonalDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignmentRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.question.DebugQuestionsRepository
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * ESUP-2223: the check-in link status, its NDelius events and the link-sent gate. The feature
 * flag is switched on per test, so this shares the default test context.
 */
class CheckinLinkIT : IntegrationTestBase() {

  @Autowired
  private lateinit var linkService: CheckinLinkService

  @Autowired
  private lateinit var checkinCreationService: CheckinCreationService

  @Autowired
  private lateinit var checkinService: CheckinService

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  private lateinit var deliusEventRepository: CheckinDeliusEventRepository

  @Autowired
  private lateinit var questionListAssignmentRepository: QuestionListAssignmentRepository

  @Autowired
  private lateinit var debugQuestionsRepository: DebugQuestionsRepository

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
    ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore + Feature.ESUP_2223)
  }

  @AfterEach
  fun tearDown() {
    ReflectionTestUtils.setField(appConfig, "enabledFeatures", featuresBefore)
    questionListAssignmentRepository.deleteAll()
    jdbcTemplate.update("TRUNCATE TABLE checkin_delius_event_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE event_audit_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE generic_notification_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE outbox_items RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_checkin_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_v2 RESTART IDENTITY CASCADE")
  }

  @Test
  fun `a check-in created with the feature on is waiting for its link`() {
    val offender = saveOffender()

    val checkin = transactionTemplate.execute { checkinCreationService.createCheckinForOffender(offender, today, "PRACT001", contactDetails(offender)) }!!

    assertThat(checkinRepository.findByUuid(checkin.uuid).get().linkStatus).isEqualTo(CheckinLinkStatus.SENDING)
  }

  @Test
  fun `link sent is recorded once and tells NDelius`() {
    val checkin = saveCheckin(saveOffender(), CheckinLinkStatus.SENDING)

    assertThat(linkService.linkSent(checkin.id, "SMS")).isTrue()
    assertThat(linkService.linkSent(checkin.id, "SMS")).isFalse()

    assertThat(checkinRepository.findByUuid(checkin.uuid).get().linkStatus).isEqualTo(CheckinLinkStatus.SENT)
    val event = deliusEventRepository.findAllByCheckinUuidOrderById(checkin.uuid).single()
    assertThat(event.eventType).isEqualTo(DomainEventType.V2_CHECKIN_LINK_DELIVERED)
    assertThat(event.notes).startsWith("Link sent by text message: ")
  }

  @Test
  fun `a link sent after check-ins were stopped is marked sent without telling NDelius`() {
    val checkin = saveCheckin(saveOffender(), CheckinLinkStatus.SENDING)
    checkinRepository.save(checkin.also { it.status = CheckinStatus.CANCELLED })

    assertThat(linkService.linkSent(checkin.id, "SMS")).isTrue()

    assertThat(checkinRepository.findByUuid(checkin.uuid).get().linkStatus).isEqualTo(CheckinLinkStatus.SENT)
    assertThat(deliusEventRepository.findAll()).isEmpty()
  }

  @Test
  fun `check-ins created before the feature are left alone`() {
    val checkin = saveCheckin(saveOffender(), linkStatus = null)

    assertThat(linkService.linkSent(checkin.id, "SMS")).isFalse()
    assertThat(linkService.linkFailed(checkin.id, CheckinLinkService.NOTIFY_FAILED)).isFalse()

    assertThat(checkinRepository.findByUuid(checkin.uuid).get().status).isEqualTo(CheckinStatus.CREATED)
    assertThat(deliusEventRepository.findAll()).isEmpty()
  }

  @Test
  fun `a failed link cancels the check-in, keeps its questions for the next one and schedules it`() {
    val offender = saveOffender()
    val listId = debugQuestionsRepository.findDefaultListId()!!
    val checkin = saveCheckin(offender, CheckinLinkStatus.SENDING)
    transactionTemplate.executeWithoutResult { questionListAssignmentRepository.createAssignment(offender.id, listId, checkinId = checkin.id) }

    assertThat(linkService.linkFailed(checkin.id, CheckinLinkService.NOTIFY_FAILED)).isTrue()

    val failed = checkinRepository.findByUuid(checkin.uuid).get()
    assertThat(failed.status).isEqualTo(CheckinStatus.CANCELLED)
    assertThat(failed.linkStatus).isEqualTo(CheckinLinkStatus.FAILED)
    assertThat(questionListAssignmentRepository.checkinAssignment(checkin.id)).isNull()
    val event = deliusEventRepository.findAllByCheckinUuidOrderById(checkin.uuid).single()
    assertThat(event.eventType).isEqualTo(DomainEventType.V2_CHECKIN_LINK_NOT_DELIVERED)
    assertThat(event.notes).isEqualTo("Reason the link was not sent: ${CheckinLinkService.NOTIFY_FAILED}")
    // the failed check-in keeps its date, so the next is a week later
    assertThat(checkinRepository.findAllByOffenderAndStatus(offender, CheckinStatus.SCHEDULED).single().dueDate).isEqualTo(today.plusWeeks(1))
  }

  @Test
  fun `the creation job doesn't create another check-in for a day whose link failed`() {
    val failed = saveOffender()
    val cancelled = saveOffender()
    saveCheckin(failed, CheckinLinkStatus.FAILED, CheckinStatus.CANCELLED)
    saveCheckin(cancelled, linkStatus = null, status = CheckinStatus.CANCELLED)

    val eligible = offenderRepository.findEligibleForCheckinCreation(today, today.plusDays(1), chunkSize = 10).map { it.id }

    assertThat(eligible).containsExactly(cancelled.id)
  }

  @Test
  fun `reminders are only sent for links that were sent`() {
    val sending = saveCheckin(saveOffender(), CheckinLinkStatus.SENDING)
    val sent = saveCheckin(saveOffender(), CheckinLinkStatus.SENT)
    val legacy = saveCheckin(saveOffender(), linkStatus = null)

    val eligible = transactionTemplate.execute {
      checkinRepository.findEligibleForReminder(today, "OffenderCheckinReminder", clock.instant()).use { stream -> stream.map { it.id }.toList() }
    }

    assertThat(eligible).containsExactlyInAnyOrder(sent.id, legacy.id).doesNotContain(sending.id)
  }

  @Test
  fun `a check-in can't be started or submitted until its link is sent`() {
    val offender = saveOffender()
    val checkin = saveCheckin(offender, CheckinLinkStatus.SENDING)

    val exception = assertThrows<ResponseStatusException> {
      checkinService.validateIdentity(checkin.uuid, PersonalDetails(offender.crn, Name("John", "Smith"), LocalDate.of(1980, 1, 1)))
    }
    assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
  }

  private fun saveOffender(): Offender = offenderRepository.save(
    offenderTemplate.copy(
      uuid = UUID.randomUUID(),
      crn = "X${(100000..999999).random()}",
      status = OffenderStatus.VERIFIED,
      firstCheckin = clock.today(),
      checkinInterval = CheckinInterval.WEEKLY,
    ).toEntity(),
  )

  private fun saveCheckin(offender: Offender, linkStatus: CheckinLinkStatus?, status: CheckinStatus = CheckinStatus.CREATED): OffenderCheckin = checkinRepository.save(
    OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = offender,
      status = status,
      dueDate = today,
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
    ).also { it.linkStatus = linkStatus },
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
