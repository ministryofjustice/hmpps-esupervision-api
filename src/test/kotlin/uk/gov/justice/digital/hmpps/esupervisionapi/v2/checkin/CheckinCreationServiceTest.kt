package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class CheckinCreationServiceTest {
  private val clock = Clock.fixed(Instant.parse("2026-11-02T09:00:00Z"), ZoneId.of("Europe/London"))
  private val today = LocalDate.now(clock)
  private val checkinRepository: OffenderCheckinRepository = mock()
  private val checkinPersistenceService: CheckinPersistenceService = mock()
  private val service = CheckinCreationService(
    clock = clock,
    offenderRepository = mock(),
    checkinRepository = checkinRepository,
    ndiliusApiClient = mock(),
    notificationService = mock(),
    eventAuditService = mock(),
    checkinWindow = Duration.ofHours(72),
    transactionTemplate = mock(),
    checkinPersistenceService = checkinPersistenceService,
  )

  private val offender = offenderTemplate.toEntity().also { ReflectionTestUtils.setField(it, "id", 7L) }
  private val scheduled = OffenderCheckin(
    uuid = UUID.randomUUID(),
    offender = offender,
    status = CheckinStatus.SCHEDULED,
    dueDate = today,
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
  ).also { ReflectionTestUtils.setField(it, "id", 11L) }

  @BeforeEach
  fun setUp() {
    whenever(checkinRepository.findScheduledForOffenders(listOf(offender.id), today)).thenReturn(listOf(scheduled))
    // activation refused
    whenever(checkinPersistenceService.checkinCreation(any(), any())).thenReturn(null)
  }

  @Test
  fun `an upcoming check-in cancelled before it could be activated is a conflict`() {
    whenever(checkinRepository.existsCreated(offender.id, today)).thenReturn(false)

    val exception = assertThrows<ResponseStatusException> {
      service.createCheckinForOffender(offender, today, "PRACT001", contactDetails())
    }

    assertEquals(HttpStatus.CONFLICT, exception.statusCode)
  }

  @Test
  fun `an upcoming check-in whose date already has a check-in is reported as not created`() {
    whenever(checkinRepository.existsCreated(offender.id, today)).thenReturn(true)

    assertNull(service.createCheckinForOffender(offender, today, "PRACT001", contactDetails()))
  }

  private fun contactDetails() = ContactDetails(
    crn = offender.crn,
    name = Name("John", "Smith"),
    email = "pop@example.com",
    practitioner = PractitionerDetails(name = Name("Jane", "Jones"), email = "practitioner@example.com"),
    events = listOf(Event(number = 1L, mainOffence = CodedDescription("OFF01", "Test"), sentence = null)),
    dateOfBirth = LocalDate.of(1980, 1, 1),
  )
}
