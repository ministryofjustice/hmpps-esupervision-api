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

  @Test
  fun `checkinIneligibilityReason - contact suspended takes priority even with active events`() {
    val offender = offenderTemplate.toEntity()
    assertEquals(
      CheckinIneligibilityReason.CONTACT_SUSPENDED,
      checkinIneligibilityReason(offender, contactDetails(events = listOf(anEvent), contactSuspended = true)),
    )
  }

  @Test
  fun `checkinIneligibilityReason - no active events when events list is explicitly empty`() {
    val offender = offenderTemplate.toEntity()
    assertEquals(
      CheckinIneligibilityReason.NO_ACTIVE_EVENTS,
      checkinIneligibilityReason(offender, contactDetails(events = emptyList())),
    )
  }

  @Test
  fun `checkinIneligibilityReason - no active events takes priority over contact suspended`() {
    val offender = offenderTemplate.toEntity()
    assertEquals(
      CheckinIneligibilityReason.NO_ACTIVE_EVENTS,
      checkinIneligibilityReason(offender, contactDetails(events = emptyList(), contactSuspended = true)),
    )
  }

  @Test
  fun `checkinIneligibilityReason - eligible when an active event exists and not suspended`() {
    val offender = offenderTemplate.toEntity()
    assertNull(checkinIneligibilityReason(offender, contactDetails(events = listOf(anEvent))))
  }

  @Test
  fun `checkinIneligibilityReason - eligible when offender has a cached current event`() {
    val offender = offenderTemplate.toEntity().apply { currentEvent = 7L }
    assertNull(checkinIneligibilityReason(offender, contactDetails(events = emptyList())))
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
