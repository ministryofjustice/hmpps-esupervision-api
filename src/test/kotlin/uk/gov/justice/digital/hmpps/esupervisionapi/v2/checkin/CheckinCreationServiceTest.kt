package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinPersistenceService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PartialCheckinCreatedEvent
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class CheckinCreationServiceTest {
  private val clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC)
  private val appConfig: AppConfig = mock()
  private val persistence: CheckinPersistenceService = mock()
  private val service = CheckinCreationService(clock, mock(), mock(), mock(), mock(), mock(), Duration.ofDays(3), mock(), persistence, appConfig)
  private val offender = offenderTemplate.toEntity()
  private val today = LocalDate.now(clock)
  private val sentence = Event.Sentence(today.minusMonths(1), "Community order", today.plusMonths(6))
  private val details = ContactDetails(
    crn = offender.crn,
    name = Name("John", "Doe"),
    dateOfBirth = LocalDate.of(1980, 1, 1),
    events = listOf(
      Event(1L, CodedDescription("X", "An offence"), sentence),
      Event(2L, CodedDescription("Y", "Another offence"), sentence.copy(expectedEndDate = null)),
    ),
  )

  @ParameterizedTest
  @CsvSource("true,true", "true,false", "false,true", "false,false")
  fun `ambiguous selection is rejected before direct or batch persistence only when enabled`(enabled: Boolean, batch: Boolean) {
    whenever(appConfig.esup2305DeliusEventSelection).thenReturn(enabled)
    val create = {
      if (batch) {
        val checkin = service.prepareCheckinForOffender(offender, today)
        val event = PartialCheckinCreatedEvent(
          offenderId = offender.id,
          practitionerId = checkin.createdBy,
          checkin = checkin.dto(details, clock = clock, checkinWindow = service.checkinWindowPeriod),
          offenderContactPreference = offender.contactPreference,
          currentEvent = null,
          checkinMode = offender.mode,
        )
        service.createCheckins(listOf(checkin to event))
      } else {
        service.createCheckinForOffender(offender, today, "SYSTEM", details)
      }
    }

    if (enabled) {
      val exception = if (batch) {
        assertThrows<BatchCheckinCreationException> { create() }.cause as ResponseStatusException
      } else {
        assertThrows<ResponseStatusException> { create() }
      }
      assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, exception.statusCode)
      verify(persistence, never()).checkinCreation(any(), any())
    } else {
      create()
      verify(persistence).checkinCreation(any(), any())
    }
    assertNull(offender.currentEvent)
  }

  @Test
  fun `creation preserves a valid saved selection even when other sentences cannot be ranked`() {
    whenever(appConfig.esup2305DeliusEventSelection).thenReturn(true)
    offender.currentEvent = 1L

    val checkin = service.createCheckinForOffender(offender, today, "SYSTEM", details)

    val event = argumentCaptor<PartialCheckinCreatedEvent>()
    verify(persistence).checkinCreation(eq(checkin), event.capture())
    assertEquals(1L, event.firstValue.currentEvent)
  }

  @Test
  fun `creation carries the inferred latest event in its event without saving a selection`() {
    whenever(appConfig.esup2305DeliusEventSelection).thenReturn(true)
    val datedDetails = details.copy(events = listOf(details.events.first(), details.events.last().copy(sentence = sentence.copy(expectedEndDate = today.plusYears(1)))))

    val checkin = service.createCheckinForOffender(offender, today, "SYSTEM", datedDetails)

    val event = argumentCaptor<PartialCheckinCreatedEvent>()
    verify(persistence).checkinCreation(eq(checkin), event.capture())
    assertEquals(2L, event.firstValue.currentEvent)
    assertNull(offender.currentEvent)
  }
}
