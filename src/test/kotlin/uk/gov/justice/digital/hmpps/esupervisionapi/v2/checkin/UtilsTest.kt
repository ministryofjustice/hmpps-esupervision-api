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
import java.time.ZoneId

class UtilsTest {

  val clock: Clock = Clock.system(ZoneId.of("Europe/London"))

  private fun contactDetails(
    events: List<Event>? = null,
    contactSuspended: Boolean = false,
  ) = ContactDetails(
    crn = "X000000",
    name = Name("John", "Doe"),
    events = events,
    contactSuspended = contactSuspended,
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
  fun `checkinIneligibilityReason - eligible when events list is absent (indeterminate, not empty)`() {
    // A null/absent events list may indicate a partial or degraded NDelius response; it must not
    // off-board an active person. Only an explicit empty list counts as "no active events".
    val offender = offenderTemplate.toEntity()
    assertNull(checkinIneligibilityReason(offender, contactDetails(events = null)))
  }

  @Test
  fun `checkinIneligibilityReason - contact suspended deactivates even when events are absent`() {
    val offender = offenderTemplate.toEntity()
    assertEquals(
      CheckinIneligibilityReason.CONTACT_SUSPENDED,
      checkinIneligibilityReason(offender, contactDetails(events = null, contactSuspended = true)),
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
