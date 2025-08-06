package uk.gov.justice.digital.hmpps.esupervisionapi.integration.jobs

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.create
import uk.gov.justice.digital.hmpps.esupervisionapi.jobs.NotifierContext
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.SingleNotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CheckinNotifierTest {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.of("Europe/London"))
  val today: LocalDate = clock.instant().atZone(clock.zone).toLocalDate()
  val practitioner = Practitioner.create("Bob")
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
}
