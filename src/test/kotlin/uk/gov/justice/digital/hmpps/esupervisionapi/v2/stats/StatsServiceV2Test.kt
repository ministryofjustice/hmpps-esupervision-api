package uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.StatsSummaryProviderMonthRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.StatsSummaryRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.TotalFeedbackMonthlyRepository
import java.time.LocalDate

class StatsServiceV2Test {

  private val monthlyFeedbackRepository: TotalFeedbackMonthlyRepository = mock()
  private val monthRepository: StatsSummaryProviderMonthRepository = mock()
  private val statsSummaryRepository: StatsSummaryRepository = mock()

  private val service =
    StatsServiceV2(
      statsSummaryRepository = statsSummaryRepository,
    )

  @Test
  fun `getStatsForMonths throws when fromMonth is not less than toMonth`() {
    var exception =
      assertThrows(IllegalArgumentException::class.java) {
        service.getStatsForMonths(
          fromMonth = LocalDate.parse("2026-02-01"),
          toMonth = LocalDate.parse("2026-01-01"),
        )
      }
    assertEquals("fromMonth must be < toMonth", exception.message)

    exception =
      assertThrows(IllegalArgumentException::class.java) {
        service.getStatsForMonths(
          fromMonth = LocalDate.parse("2026-01-01"),
          toMonth = LocalDate.parse("2026-01-01"),
        )
      }
    assertEquals("fromMonth must be < toMonth", exception.message)
  }
}
