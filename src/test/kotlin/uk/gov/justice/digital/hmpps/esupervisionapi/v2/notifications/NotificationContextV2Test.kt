package uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class NotificationContextV2Test {

  private val clock = Clock.fixed(Instant.parse("2025-01-15T12:00:00Z"), ZoneId.of("Europe/London"))
  private val env = "unit-test"

  @Test
  fun `generateReference returns correct format`() {
    assertEquals("OSUB-2025-01-15-unit-test", NotificationContextV2.generateReference(NotificationType.OffenderCheckinSubmitted, clock, env))
    assertEquals("OSTP-2025-01-15-unit-test", NotificationContextV2.generateReference(NotificationType.OffenderCheckinsStopped, clock, env))
    assertEquals("OREG-2025-01-15-unit-test", NotificationContextV2.generateReference(NotificationType.RegistrationConfirmation, clock, env))
    assertEquals("OCHK-2025-01-15-unit-test", NotificationContextV2.generateReference(NotificationType.OffenderCheckinInvite, clock, env))
    assertEquals("OREM-2025-01-15-unit-test", NotificationContextV2.generateReference(NotificationType.OffenderCheckinReminder, clock, env))
    assertEquals("PSUB-2025-01-15-unit-test", NotificationContextV2.generateReference(NotificationType.PractitionerCheckinSubmitted, clock, env))
    assertEquals("PEXP-2025-01-15-unit-test", NotificationContextV2.generateReference(NotificationType.PractitionerCheckinMissed, clock, env))
    assertEquals("PING-2025-01-15-unit-test", NotificationContextV2.generateReference(NotificationType.PractitionerInviteIssueGeneric, clock, env))
  }
}
