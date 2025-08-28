package uk.gov.justice.digital.hmpps.esupervisionapi.integration.offender

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinInterval

class OffenderInfoTest {
  @Test
  fun `CheckinInterval round trip`() {
    CheckinInterval.entries.forEach { entry ->
      val rt = CheckinInterval.fromDuration(entry.duration)
      Assertions.assertEquals(entry, rt, "Round-tripped checkin interval differs")
    }
  }

  @Test
  fun `CheckinInterval ordering`() {
    val expectedOrdering = listOf(
      CheckinInterval.WEEKLY,
      CheckinInterval.TWO_WEEKS,
      CheckinInterval.FOUR_WEEKS,
      CheckinInterval.EIGHT_WEEKS,
    )

    val ordered = CheckinInterval.entries.sorted()

    Assertions.assertEquals(expectedOrdering, ordered, "Ordered checkin interval differs")

    ordered.zipWithNext().forEach { (i1, i2) ->
      Assertions.assertTrue(i1.days < i2.days, "Expected small interval to have fewer days")
    }
  }
}
