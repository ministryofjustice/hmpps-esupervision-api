package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

class EventDetailV2ServiceTest {

  private val offenderRepository: OffenderV2Repository = mock()
  private val checkinRepository: OffenderCheckinV2Repository = mock()

  private lateinit var service: EventDetailV2Service

  @BeforeEach
  fun setUp() {
    service = EventDetailV2Service(offenderRepository, checkinRepository)
  }

  @Nested
  inner class GetEventDetail {

    @Test
    fun `returns setup-completed event detail`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(uuid)
      whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))

      val result = service.getEventDetail("/v2/events/setup-completed/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.eventType).isEqualTo("SETUP_COMPLETED")
      assertThat(result.crn).isEqualTo("X123456")
      assertThat(result.offenderUuid).isEqualTo(uuid)
    }

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
    fun `formats checkin notes with human-readable datetime`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val submittedAt = Instant.parse("2025-06-15T14:30:00Z")
      val checkin = createCheckin(uuid, offender, submittedAt = submittedAt)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("Check in submitted: 15 June 2025 at 3:30pm")
    }

    @Test
    fun `formats automated ID check result`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(uuid, offender, autoIdCheck = AutomatedIdVerificationResult.MATCH)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("Automated ID check: Match")
    }

    @Test
    fun `formats automated ID check NO_FACE_DETECTED`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val checkin = createCheckin(uuid, offender, autoIdCheck = AutomatedIdVerificationResult.NO_FACE_DETECTED)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("Automated ID check: No face detected")
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
      assertThat(result!!.notes).contains("Manual ID check: Match")
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
      assertThat(result!!.notes).doesNotContain("Manual ID check")
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
      assertThat(result!!.notes).contains("Survey response:")
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
        "someFlag" to true,
        "anotherFlag" to false,
      )
      val checkin = createCheckin(uuid, offender, surveyResponse = surveyResponse)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("Some flag: Yes")
      assertThat(result.notes).contains("Another flag: No")
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
      assertThat(result!!.notes).contains("Anything they need support with: Mental Health, Need Housing")
    }

    @Test
    fun `converts camelCase field names to human readable`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(UUID.randomUUID())
      val surveyResponse = mapOf(
        "someNewField" to "value",
      )
      val checkin = createCheckin(uuid, offender, surveyResponse = surveyResponse)
      whenever(checkinRepository.findByUuid(uuid)).thenReturn(Optional.of(checkin))

      val result = service.getEventDetail("/v2/events/checkin-submitted/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("Some new field: value")
    }
  }

  @Nested
  inner class SetupCompletedNotesFormatting {

    @Test
    fun `formats setup completed notes`() {
      val uuid = UUID.randomUUID()
      val offender = createOffender(uuid)
      whenever(offenderRepository.findByUuid(uuid)).thenReturn(Optional.of(offender))

      val result = service.getEventDetail("/v2/events/setup-completed/$uuid")

      assertThat(result).isNotNull
      assertThat(result!!.notes).contains("Registration Completed")
      assertThat(result.notes).contains("CRN: X123456")
      assertThat(result.notes).contains("Practitioner: PRACT001")
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
  )
}
