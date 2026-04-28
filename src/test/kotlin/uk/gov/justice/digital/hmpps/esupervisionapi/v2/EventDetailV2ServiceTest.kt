package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.ProxyLinkCreator
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.LivenessResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

class EventDetailV2ServiceTest {

  private val checkinRepository: OffenderCheckinV2Repository = mock()
  private val eventLogRepository: OffenderEventLogV2Repository = mock()
  private val proxyLinkCreator: ProxyLinkCreator = mock()
  private val appConfig: AppConfig = mock()

  private lateinit var service: EventDetailV2Service

  @BeforeEach
  fun setUp() {
    service = EventDetailV2Service(checkinRepository, eventLogRepository, proxyLinkCreator, appConfig)
  }

  @Nested
  inner class GetEventDetail {

    @Test
    fun `returns checkin-submitted event detail with submittedAt timestamp`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val submittedAt = Instant.parse("2025-06-15T14:30:00Z")
      val checkin = createCheckin(uuid, offender, submittedAt = submittedAt)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.eventType).isEqualTo("CHECKIN_SUBMITTED")
      assertThat(result.timestamp).isEqualTo(submittedAt)
    }

    @Test
    fun `returns checkin-reviewed event detail with reviewedAt timestamp`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val reviewedAt = Instant.parse("2025-06-16T10:00:00Z")
      val checkin = createCheckin(uuid, offender, reviewedAt = reviewedAt)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-reviewed/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.eventType).isEqualTo("CHECKIN_REVIEWED")
      assertThat(result.timestamp).isEqualTo(reviewedAt)
    }
  }

  @Nested
  inner class CheckinNotesFormatting {

    @Test
    fun `formats checkin notes for submitted event`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val submittedAt = Instant.parse("2025-06-15T14:30:00Z")
      val checkin = createCheckin(uuid, offender, submittedAt = submittedAt)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("Check in status: Submitted")
    }

    @Test
    fun `formats checkin notes for missed checkin event`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(uuid, offender, status = CheckinV2Status.EXPIRED)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val logEntry = OffenderCheckinLogEntryV2Dto(
        UUID.randomUUID(),
        notes = "Technical error",
        createdAt = Instant.now(),
        logEntryType = LogEntryType.OFFENDER_CHECKIN_NOT_SUBMITTED,
        practitioner = "PRACT001",
        checkin = uuid,
      )

      whenever(eventLogRepository.findAllCheckinEvents(checkin, setOf(LogEntryType.OFFENDER_CHECKIN_NOT_SUBMITTED)))
        .thenReturn(listOf(logEntry))

      val result = service.getEventDetail("/v2/events/checkin-expired/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("Check in status: Missed")
      assertThat(result.notes).contains("Why did they not complete their check in: Technical error")
    }

    @Test
    fun `formats automated ID check result`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(uuid, offender, autoIdCheck = AutomatedIdVerificationResult.MATCH)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("System ID check result: Pass")
    }

    @Test
    fun `passes when liveness enabled and both face match and liveness pass`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(
        uuid,
        offender,
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
        livenessEnabled = true,
        livenessResult = LivenessResult.LIVE,
      )
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("System ID and liveness check result: Pass")
      assertThat(result.notes).doesNotContain("System ID check result:")
    }

    @Test
    fun `fails when liveness enabled and liveness fails even if face matches`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(
        uuid,
        offender,
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
        livenessEnabled = true,
        livenessResult = LivenessResult.NOT_LIVE,
      )
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("System ID and liveness check result: Fail")
    }

    @Test
    fun `fails when liveness enabled and face does not match even if liveness passes`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(
        uuid,
        offender,
        autoIdCheck = AutomatedIdVerificationResult.NO_MATCH,
        livenessEnabled = true,
        livenessResult = LivenessResult.LIVE,
      )
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("System ID and liveness check result: Fail")
    }

    @Test
    fun `fails when liveness enabled but liveness result missing`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(
        uuid,
        offender,
        autoIdCheck = AutomatedIdVerificationResult.MATCH,
        livenessEnabled = true,
        livenessResult = null,
      )
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("System ID and liveness check result: Fail")
    }

    @Test
    fun `formats automated ID check NO_FACE_DETECTED`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(uuid, offender, autoIdCheck = AutomatedIdVerificationResult.NO_FACE_DETECTED)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("System ID check result: Fail")
    }

    @Test
    fun `includes manual ID check for reviewed events`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(
        uuid,
        offender,
        autoIdCheck = AutomatedIdVerificationResult.NO_MATCH,
        manualIdCheck = ManualIdVerificationResult.MATCH,
        status = CheckinV2Status.REVIEWED,
      )
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-reviewed/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("Is the person in the video the correct person: Yes")
    }

    @Test
    fun `excludes manual ID check for submitted events`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(
        uuid,
        offender,
        autoIdCheck = AutomatedIdVerificationResult.NO_MATCH,
        manualIdCheck = ManualIdVerificationResult.MATCH,
        status = CheckinV2Status.SUBMITTED,
      )
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).doesNotContain("Is the person in the video the correct person")
    }
  }

  @Nested
  inner class SurveyResponseFormatting {

    @Test
    fun `formats survey response with custom labels`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val surveyResponse = mapOf(
        "mentalHealth" to "FEELING_GREAT",
        "callback" to "YES",
      )
      val checkin = createCheckin(uuid, offender, surveyResponse = surveyResponse)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("Check in answers:")
      assertThat(result.notes).contains("How they have been feeling: Feeling Great")
      assertThat(result.notes).contains("If they need us to contact them before their next appointment: Yes")
    }

    @Test
    fun `converts SCREAMING_SNAKE_CASE enum values`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val surveyResponse = mapOf(
        "mentalHealth" to "NOT_GREAT",
      )
      val checkin = createCheckin(uuid, offender, surveyResponse = surveyResponse)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("How they have been feeling: Not Great")
    }

    @Test
    fun `formats YES and NO values`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val surveyResponse = mapOf(
        "callback" to "YES",
      )
      val checkin = createCheckin(uuid, offender, surveyResponse = surveyResponse)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("Yes")
    }

    @Test
    fun `skips telemetry fields`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val surveyResponse = mapOf(
        "device" to "iPhone",
        "version" to "2025-07-10@pilot",
        "mentalHealth" to "FEELING_GOOD",
      )
      val checkin = createCheckin(uuid, offender, surveyResponse = surveyResponse)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).doesNotContain("Device:")
      assertThat(result.notes).doesNotContain("Version:")
      assertThat(result.notes).contains("How they have been feeling: Feeling Good")
    }

    @Test
    fun `formats boolean values as Yes or No`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val surveyResponse = mapOf(
        "callback" to true,
        "callbackDetails" to false,
      )
      val checkin = createCheckin(uuid, offender, surveyResponse = surveyResponse)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("If they need us to contact them before their next appointment: Yes")
      assertThat(result.notes).contains("What they want to talk about: No")
    }

    @Test
    fun `formats list values as comma-separated`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val surveyResponse = mapOf(
        "assistance" to listOf("MENTAL_HEALTH", "NEED_HOUSING"),
      )
      val checkin = createCheckin(uuid, offender, surveyResponse = surveyResponse)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("Anything they need support with or to let us know: Mental Health, Need Housing")
    }
  }

  @Test
  fun `adds annotations`() {
    val uuid = UUID.randomUUID()
    val offender = createOffender(UUID.randomUUID())
    val checkin = createCheckin(uuid, offender, status = CheckinV2Status.SUBMITTED)
    whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))
    val logEntry = OffenderCheckinLogEntryV2Dto(
      UUID.randomUUID(),
      notes = "Some note",
      createdAt = Instant.now().minusSeconds(10),
      logEntryType = LogEntryType.OFFENDER_CHECKIN_ANNOTATED,
      practitioner = checkin.offender.practitionerId,
      checkin = uuid,
    )
    whenever(eventLogRepository.findCheckinLogByUuid(logEntry.uuid)).thenReturn(Optional.of(logEntry))

    val result = service.getEventDetail("/v2/events/checkin-annotated/${logEntry.uuid}")

    assertThat(result).isNotNull
    assertThat(result!!.notes).contains("Some note")
  }

  @Nested
  inner class SensitiveFlagMapping {

    @Test
    fun `includes sensitive flag for reviewed events`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(uuid, offender, status = CheckinV2Status.REVIEWED).apply {
        sensitive = true
      }
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-reviewed/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.sensitive).isTrue()
    }

    @Test
    fun `includes sensitive flag for annotated events`() {
      val checkinUuid = UUID.randomUUID()
      val annotationUuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())

      val checkin = createCheckin(checkinUuid, offender, status = CheckinV2Status.REVIEWED).apply {
        sensitive = true
      }

      val logEntry = OffenderCheckinLogEntryV2Dto(
        annotationUuid,
        notes = "Sensitive annotation",
        createdAt = Instant.now(),
        logEntryType = LogEntryType.OFFENDER_CHECKIN_ANNOTATED,
        practitioner = "PRACT001",
        checkin = checkinUuid,
      )

      whenever(checkinRepository.findByUuid(checkinUuid)).thenReturn(Optional.of(checkin))
      whenever(eventLogRepository.findCheckinLogByUuid(annotationUuid)).thenReturn(Optional.of(logEntry))

      val result = service.getEventDetail("/v2/events/checkin-annotated/$annotationUuid")

      assertThat(result!!.sensitive).isNotNull()
      assertThat(result.sensitive).isTrue()
    }

    @Test
    fun `sensitive flag is false when not explicitly set in check in reviewed`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(uuid, offender, status = CheckinV2Status.REVIEWED)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-reviewed/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.sensitive).isFalse()
    }

    @Test
    fun `sensitive flag is false when not explicitly set in check in annotated`() {
      val checkinUuid = UUID.randomUUID()
      val annotationUuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())

      val checkin = createCheckin(checkinUuid, offender, status = CheckinV2Status.REVIEWED)

      val logEntry = OffenderCheckinLogEntryV2Dto(
        annotationUuid,
        notes = "Test note",
        createdAt = Instant.now(),
        logEntryType = LogEntryType.OFFENDER_CHECKIN_ANNOTATED,
        practitioner = "PRACT001",
        checkin = checkinUuid,
      )

      whenever(eventLogRepository.findCheckinLogByUuid(annotationUuid)).thenReturn(Optional.of(logEntry))
      whenever(checkinRepository.findByUuid(checkinUuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-annotated/$annotationUuid")

      assertThat(result).isNotNull
      assertThat(result!!.sensitive).isFalse()
    }
  }

  private fun createOffender(uuid: UUID) = OffenderV2(
    uuid = uuid,
    crn = "X123456",
    practitionerId = "PRACT001",
    status = OffenderStatus.VERIFIED,
    firstCheckin = LocalDate.of(2025, 6, 15),
    checkinInterval = CheckinInterval.WEEKLY.duration,
    createdAt = Instant.parse("2025-06-01T10:00:00Z"),
    createdBy = "PRACT001",
    updatedAt = Instant.parse("2025-06-01T10:00:00Z"),
    contactPreference = ContactPreference.PHONE,
  )

  private fun createCheckin(
    uuid: UUID,
    offender: OffenderV2,
    status: CheckinV2Status = CheckinV2Status.SUBMITTED,
    submittedAt: Instant? = Instant.parse("2025-06-15T14:30:00Z"),
    reviewedAt: Instant? = null,
    autoIdCheck: AutomatedIdVerificationResult? = null,
    manualIdCheck: ManualIdVerificationResult? = null,
    surveyResponse: Map<String, Any>? = null,
    sensitive: Boolean = false,
    livenessEnabled: Boolean = false,
    livenessResult: LivenessResult? = null,
  ) = OffenderCheckinV2(
    uuid = uuid,
    offender = offender,
    status = status,
    dueDate = LocalDate.of(2025, 6, 15),
    createdAt = Instant.parse("2025-06-14T10:00:00Z"),
    createdBy = "SYSTEM",
    submittedAt = submittedAt,
    reviewedAt = reviewedAt,
    autoIdCheck = autoIdCheck,
    manualIdCheck = manualIdCheck,
    surveyResponse = surveyResponse,
    sensitive = sensitive,
    livenessEnabled = livenessEnabled,
    livenessResult = livenessResult,
  )
}
