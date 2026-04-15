package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import java.time.Clock
import java.time.ZoneId

class UtilsTest {

  val clock: Clock = Clock.system(ZoneId.of("Europe/London"))

  @Test
  fun `next checkin day`() {
    val today = clock.today()
    val offender = offenderTemplate.copy(firstCheckin = today.minusDays(6)).toEntity()
    val next = nextCheckinDay(offender, today)
    assertEquals(today.plusDays(1), next)
  }

  @Test
  fun `next checkin day - today is first checkin`() {
    val today = clock.today()
    val offender = offenderTemplate.copy(firstCheckin = today).toEntity()
    assertEquals(today.plusDays(7), nextCheckinDay(offender, today))
  }

  @Test
  fun `next checkin day - today-7 is first checkin`() {
    val today = clock.today()
    val offender = offenderTemplate.copy(firstCheckin = today.minusDays(7)).toEntity()
    assertEquals(today.plusDays(7), nextCheckinDay(offender, today))
  }
}
