package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

class UtilsTest {

  val clock: Clock = Clock.system(ZoneId.of("Europe/London"))

  private fun contactDetails(
    events: List<Event> = emptyList(),
    contactSuspended: Boolean = false,
  ) = ContactDetails(
    crn = "X000000",
    name = Name("John", "Doe"),
    events = events,
    contactSuspended = contactSuspended,
    dateOfBirth = LocalDate.of(1980, 1, 1),
  )

  private val anEvent = Event(number = 1L, mainOffence = CodedDescription("X", "An offence"), sentence = null)
  private val today = LocalDate.of(2026, 10, 26)

  private fun eventEnding(number: Long, expectedEndDate: LocalDate) = Event(
    number = number,
    mainOffence = CodedDescription("X", "An offence"),
    sentence = Event.Sentence(
      date = LocalDate.of(2026, 1, 1),
      description = "Sentence",
      expectedEndDate = expectedEndDate,
    ),
  )

  @Test
  fun `checkinIneligibilityReason - contact suspended takes priority even with active events`() {
    val offender = offenderTemplate.toEntity().apply { currentEvent = 1L }
    assertEquals(
      CheckinIneligibilityReason.CONTACT_SUSPENDED,
      checkinIneligibilityReason(offender, contactDetails(events = listOf(anEvent), contactSuspended = true)),
    )
  }

  @Test
  fun `checkinIneligibilityReason - no active events when events list is explicitly empty`() {
    assertEquals(
      CheckinIneligibilityReason.NO_ACTIVE_EVENTS,
      checkinIneligibilityReason(offenderTemplate.toEntity(), contactDetails(events = emptyList())),
    )
  }

  @Test
  fun `checkinIneligibilityReason - no active events takes priority over contact suspended`() {
    assertEquals(
      CheckinIneligibilityReason.NO_ACTIVE_EVENTS,
      checkinIneligibilityReason(offenderTemplate.toEntity(), contactDetails(events = emptyList(), contactSuspended = true)),
    )
  }

  @Test
  fun `checkinIneligibilityReason - eligible when an active event exists and not suspended`() {
    val offender = offenderTemplate.toEntity().apply { currentEvent = 1L }
    assertNull(checkinIneligibilityReason(offender, contactDetails(events = listOf(anEvent))))
  }

  @Test
  fun `checkinIneligibilityReason - cached selection does not deactivate when Delius events are missing`() {
    val offender = offenderTemplate.toEntity().apply { currentEvent = 1L }
    assertNull(checkinIneligibilityReason(offender, contactDetails(events = emptyList())))
  }

  @Test
  fun `checkinIneligibilityReason - does not deactivate when selected event is missing but another is active`() {
    val offender = offenderTemplate.toEntity().apply { currentEvent = 1L }

    assertNull(checkinIneligibilityReason(offender, contactDetails(events = listOf(anEvent.copy(number = 2L)))))
  }

  @Test
  fun `checkinIneligibilityReason - does not handle selection when multiple events exist`() {
    val offender = offenderTemplate.toEntity()

    assertNull(checkinIneligibilityReason(offender, contactDetails(events = listOf(anEvent, anEvent.copy(number = 2L)))))
  }

  @Test
  fun `active event number - uses the only event when no selection is saved`() {
    val offender = offenderTemplate.toEntity()

    assertEquals(1L, activeEventNumber(offender, contactDetails(events = listOf(anEvent))))
  }

  @Test
  fun `active event number - preserves first-event fallback when no selection is saved`() {
    val offender = offenderTemplate.toEntity()

    assertEquals(1L, activeEventNumber(offender, contactDetails(events = listOf(anEvent, anEvent.copy(number = 2L)))))
  }

  @Test
  fun `active event number - uses the saved selection when multiple events exist`() {
    val offender = offenderTemplate.toEntity().apply { currentEvent = 2L }

    assertEquals(2L, activeEventNumber(offender, contactDetails(events = listOf(anEvent, anEvent.copy(number = 2L)))))
  }

  @Test
  fun `active event number - expected end date does not override Delius active event status`() {
    val offender = offenderTemplate.toEntity()
    val completed = eventEnding(1L, today.minusDays(1))

    assertEquals(1L, activeEventNumber(offender, contactDetails(events = listOf(completed))))
  }

  @Test
  fun `active event number - preserves a saved selection returned by Delius`() {
    val offender = offenderTemplate.toEntity().apply { currentEvent = 1L }
    val completed = eventEnding(1L, today.minusDays(1))

    assertEquals(1L, activeEventNumber(offender, contactDetails(events = listOf(completed))))
  }

  @Test
  fun `checkinIneligibilityReason - expected end date does not override Delius active event status`() {
    val offender = offenderTemplate.toEntity().apply { currentEvent = 1L }
    assertNull(
      checkinIneligibilityReason(offender, contactDetails(events = listOf(eventEnding(1L, today.minusDays(1))))),
    )
  }

  @Test
  fun `ineligibility reasons map to criterion-specific audit event types`() {
    assertEquals(
      OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED,
      CheckinIneligibilityReason.CONTACT_SUSPENDED.auditEventType,
    )
    assertEquals(
      OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS,
      CheckinIneligibilityReason.NO_ACTIVE_EVENTS.auditEventType,
    )
  }

  @Test
  fun `next checkin day`() {
    val today = clock.today()
    val offender = offenderTemplate.copy(firstCheckin = today.minusDays(6)).toEntity()
    assertEquals(today.plusDays(1), nextCheckinDay(offender, today))

    assertEquals(today.plusDays(1), nextCheckinDay(offender, today, CheckinScheduleLowerBound.INCLUDE_TODAY))
  }

  @Test
  fun `next checkin day - today is first checkin`() {
    val today = clock.today()
    val offender = offenderTemplate.copy(firstCheckin = today).toEntity()
    assertEquals(today.plusDays(7), nextCheckinDay(offender, today))

    assertEquals(today, nextCheckinDay(offender, today, CheckinScheduleLowerBound.INCLUDE_TODAY))
  }

  @Test
  fun `next checkin day - today-7 is first checkin`() {
    val today = clock.today()
    val offender = offenderTemplate.copy(firstCheckin = today.minusDays(7)).toEntity()
    assertEquals(today.plusDays(7), nextCheckinDay(offender, today))

    assertEquals(today, nextCheckinDay(offender, today, CheckinScheduleLowerBound.INCLUDE_TODAY))
  }
}
