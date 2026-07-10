package uk.gov.justice.digital.hmpps.esupervisionapi.integration.jobs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinNoteResend
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinNoteResendRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventDetailService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.LogEntryType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.CheckinNoteResendJob
import java.time.Clock
import java.time.Instant
import java.util.UUID

class CheckinNoteResendJobIT : IntegrationTestBase() {

  @Autowired lateinit var clock: Clock

  @Autowired lateinit var offenderRepository: OffenderRepository

  @Autowired lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired lateinit var resendRepository: CheckinNoteResendRepository

  @Autowired lateinit var eventLogRepository: OffenderEventLogRepository

  @Autowired lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired lateinit var jobLogRepository: JobLogRepository

  @Autowired lateinit var eventDetailService: EventDetailService

  @Autowired lateinit var job: CheckinNoteResendJob

  @MockitoBean lateinit var ndeliusApiClient: INdiliusApiClient

  @MockitoBean lateinit var domainEventPublisher: DomainEventPublisher

  @AfterEach
  fun cleanUp() {
    resendRepository.deleteAll()
    outboxItemRepository.deleteAll()
    eventLogRepository.deleteAll()
    checkinRepository.deleteAll()
    offenderRepository.deleteAll()
    jobLogRepository.deleteAll()
    offenderRepository.flush()
    org.mockito.kotlin.reset(ndeliusApiClient, domainEventPublisher)
  }

  @Test
  fun `resends checkin answers as an annotation note and marks everything sent`() {
    val checkin = seedSubmittedCheckin(surveyResponse = mapOf("mentalHealth" to "FEELING_GREAT"))
    resendRepository.save(CheckinNoteResend(checkin = checkin.uuid))

    job.process()

    val row = resendRepository.findAll().single()
    assertThat(row.sentAt).isNotNull()
    assertThat(row.notes).isEqualTo("SENT")
    assertThat(row.annotationUuid).isNotNull()

    val logEntry = eventLogRepository.findByUuid(row.annotationUuid!!).orElseThrow()
    assertThat(logEntry.logEntryType).isEqualTo(LogEntryType.OFFENDER_CHECKIN_ANNOTATED)
    assertThat(logEntry.practitioner).isEqualTo("SYSTEM")

    // outbox row auto-created by the offender_event_log_v2 insert trigger must be cleared
    val outboxItem = outboxItemRepository.findByTypeAndEntityId(OutboxItemType.CHECKIN_ANNOTATED, logEntry.id).orElseThrow()
    assertThat(outboxItem.status).isEqualTo(OutboxItemStatus.SENT)

    val eventCaptor = argumentCaptor<DomainEvent>()
    verify(domainEventPublisher).publish(eventCaptor.capture())
    assertThat(eventCaptor.firstValue.detailUrl).endsWith("/v2/events/checkin-annotated/${row.annotationUuid}")

    // the NDelius callback for that detail URL must return the answers
    val detail = eventDetailService.getEventDetail("/v2/events/checkin-annotated/${row.annotationUuid}")
    assertThat(detail).isNotNull()
    assertThat(detail!!.notes).contains("This comment was added due to a system issue.")
    assertThat(detail.notes).contains("Check in answers:")
    assertThat(detail.notes).contains("How they have been feeling: Feeling Great")
    assertThat(detail.crn).isEqualTo(checkin.offender.crn)

    // other tests in this class also write CHECKIN_NOTE_RESEND job logs, so assert on the latest
    val jobLog = jobLogRepository.findAll().filter { it.jobType == "CHECKIN_NOTE_RESEND" }.maxBy { it.id }
    assertThat(jobLog.endedAt).isNotNull()
  }

  @Test
  fun `re-running the job does not resend or duplicate the annotation`() {
    val checkin = seedSubmittedCheckin(surveyResponse = mapOf("mentalHealth" to "FEELING_GREAT"))
    resendRepository.save(CheckinNoteResend(checkin = checkin.uuid))

    job.process()
    job.process()

    verify(domainEventPublisher, times(1)).publish(any<DomainEvent>())
    assertThat(eventLogRepository.findAll()).hasSize(1)
  }

  @Test
  fun `unknown checkin uuid is skipped with a note and nothing is published`() {
    resendRepository.save(CheckinNoteResend(checkin = UUID.randomUUID()))

    job.process()

    val row = resendRepository.findAll().single()
    assertThat(row.sentAt).isNotNull()
    assertThat(row.notes).isEqualTo("SKIPPED: checkin not found")
    verify(domainEventPublisher, times(0)).publish(any<DomainEvent>())
  }

  @Test
  fun `checkin without survey response is skipped with a note`() {
    val checkin = seedSubmittedCheckin(surveyResponse = null)
    resendRepository.save(CheckinNoteResend(checkin = checkin.uuid))

    job.process()

    val row = resendRepository.findAll().single()
    assertThat(row.notes).isEqualTo("SKIPPED: no survey response")
    verify(domainEventPublisher, times(0)).publish(any<DomainEvent>())
  }

  @Test
  fun `sensitive flag flows through to the event detail`() {
    val checkin = seedSubmittedCheckin(surveyResponse = mapOf("mentalHealth" to "FEELING_GREAT"), sensitive = true)
    resendRepository.save(CheckinNoteResend(checkin = checkin.uuid))

    job.process()

    val row = resendRepository.findAll().single()
    val detail = eventDetailService.getEventDetail("/v2/events/checkin-annotated/${row.annotationUuid}")
    assertThat(detail!!.sensitive).isTrue()
  }

  private fun seedSubmittedCheckin(surveyResponse: Map<String, Any>?, sensitive: Boolean = false): OffenderCheckin {
    val offender: Offender = offenderTemplate
      .copy(crn = "A000001", uuid = UUID.randomUUID(), firstCheckin = clock.today(), status = OffenderStatus.VERIFIED)
      .toEntity()
    offenderRepository.save(offender)

    val checkin = OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = offender,
      status = CheckinStatus.SUBMITTED,
      dueDate = clock.today(),
      createdAt = clock.instant().minusSeconds(3600),
      createdBy = offender.practitionerId,
      submittedAt = Instant.parse("2026-05-12T14:30:00Z"),
      surveyResponse = surveyResponse,
      sensitive = sensitive,
    )
    return checkinRepository.saveAndFlush(checkin)
  }
}
