package uk.gov.justice.digital.hmpps.esupervisionapi.integration.jobs

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.PRACTITIONER_ALICE
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.create
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.createNewPractitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.datesBetweenExclusive
import uk.gov.justice.digital.hmpps.esupervisionapi.jobs.NotifierContext
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.SingleNotificationContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.UUID

class CheckinNotifierTest {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.of("Europe/London"))
  val today: LocalDate = clock.instant().atZone(clock.zone).toLocalDate()
  val practitioner = createNewPractitioner("Bob.Smith")
  val offender = Offender.create("Bob Smith", LocalDate.of(1980, 1, 1), today, CheckinInterval.WEEKLY, practitioner = practitioner)

  @Test
  fun `is checkin works`() {
    val notificationContext = SingleNotificationContext.from(java.util.UUID.randomUUID())
    var context = NotifierContext(clock, today = today, notificationContext = notificationContext)
    Assertions.assertTrue(context.isCheckinDay(offender))

    context = NotifierContext(
      clock,
      today = today.plusDays(1),
      notificationContext = SingleNotificationContext.from(java.util.UUID.randomUUID()),
    )
    Assertions.assertFalse(context.isCheckinDay(offender))

    context = NotifierContext(
      clock,
      today = today.plusDays(6),
      notificationContext = SingleNotificationContext.from(java.util.UUID.randomUUID()),
    )
    Assertions.assertFalse(context.isCheckinDay(offender))

    context = NotifierContext(
      clock,
      today = today.plusDays(7),
      notificationContext = SingleNotificationContext.from(java.util.UUID.randomUUID()),
    )
    Assertions.assertTrue(context.isCheckinDay(offender))
  }

  @Test
  fun `is checkin day on first checkin date`() {
    val today = LocalDate.now()
    val isCheckinDay = isCheckinDayOn(today, today, CheckinInterval.WEEKLY)
    Assertions.assertTrue(isCheckinDay, "Should be checkin day on date of first checkin")
  }

  @Test
  fun `is checkin day on first interval after first checkin`() {
    val firstCheckinDate = LocalDate.of(2025, 8, 27)

    CheckinInterval.entries.forEach { interval ->
      val jobDate = firstCheckinDate.plusDays(interval.duration.toDays())
      val isCheckinDay = isCheckinDayOn(jobDate, firstCheckinDate, interval)
      Assertions.assertTrue(isCheckinDay, "Expected checkin date on interval")
    }
  }

  @Test
  fun `is checkin day on interval multiple after first checkin`() {
    val firstCheckinDate = LocalDate.now()

    CheckinInterval.entries.forEach { interval ->
      (2..4).forEach { checkin ->
        val jobExecDate = firstCheckinDate.plusDays(checkin * interval.duration.toDays())
        val isCheckinDay = isCheckinDayOn(jobExecDate, firstCheckinDate, interval)
        Assertions.assertTrue(isCheckinDay, "Should be checkin day on interval multiple")
      }
    }
  }

  @Test
  fun `is not checkin day on interval before first checkin`() {
    val jobExecDate = LocalDate.now()

    CheckinInterval.entries.forEach { interval ->
      val firstCheckinDate = jobExecDate.plusDays(interval.duration.toDays())
      val isCheckinDate = isCheckinDayOn(jobExecDate, firstCheckinDate, interval)
      Assertions.assertFalse(isCheckinDate, "Should not be checkin date before first checkin")
    }
  }

  @Test
  fun `is not checkin day between first checkin and interval`() {
    val firstCheckinDate = LocalDate.now()

    CheckinInterval.entries.forEach { interval ->
      val nextCheckinDate = firstCheckinDate.plusDays(interval.duration.toDays())

      datesBetweenExclusive(firstCheckinDate, nextCheckinDate).forEach { jobExecDate ->
        val isCheckinDay = isCheckinDayOn(jobExecDate, firstCheckinDate, interval)
        Assertions.assertFalse(isCheckinDay, "Should not be checkin day between first checkin and interval")
      }
    }
  }

  /**
   * Returns whether it's checkin day for a PoP if the checkin invites job runs on the given date for a PoP with
   * the specified first checkin date and checkin interval
   */
  fun isCheckinDayOn(jobExecDate: LocalDate, firstCheckinDate: LocalDate, checkinInterval: CheckinInterval): Boolean {
    val zone = ZoneId.of("Europe/London")

    val jobTime = jobExecDate.atTime(10, 17)
    val clock = Clock.fixed(jobTime.atZone(zone).toInstant(), zone)

    val notificationContext = SingleNotificationContext.from(UUID.randomUUID())

    val context = NotifierContext(
      clock,
      jobExecDate,
      notificationLeadTime = Period.ofDays(0),
      checkinDate = jobExecDate,
      notificationContext = notificationContext,
    )

    val offender = Offender.create(
      "Bob Smith",
      dateOfBirth = LocalDate.of(1980, 6, 3),
      firstCheckinDate,
      checkinInterval = checkinInterval,
      practitioner = PRACTITIONER_ALICE,
    )

    return context.isCheckinDay(offender)
  }
}
