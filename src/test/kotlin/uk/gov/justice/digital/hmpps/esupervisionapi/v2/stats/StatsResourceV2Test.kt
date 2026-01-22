package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsResourceV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsServiceV2
import java.math.BigDecimal

class StatsResourceV2Test {

  private val service: StatsServiceV2 = mock()
  private val resource = StatsResourceV2(service)

  @Test
  fun `getStats returns expected StatsResponse`() {
    val summary = StatsSummary(
      singleton = 1,
      totalSignedUp = 10,
      activeUsers = 7,
      inactiveUsers = 3,
      completedCheckins = 20,
      notCompletedOnTime = 2,
      avgHoursToComplete = BigDecimal.valueOf(5.5),
      avgCompletedCheckinsPerPerson = BigDecimal.valueOf(2.86),
    )

    whenever(service.getStats()).thenReturn(summary)

    val response = resource.getStats()

    assertEquals(HttpStatus.OK, response.statusCode)
    val body = response.body!!
    assertEquals(10, body.totalSignedUp)
    assertEquals(7, body.activeUsers)
    assertEquals(3, body.inactiveUsers)
    assertEquals(20, body.completedCheckins)
    assertEquals(2, body.notCompletedOnTime)
    assertEquals(BigDecimal.valueOf(5.5), body.avgHoursToComplete)
    assertEquals(BigDecimal.valueOf(2.86), body.avgCompletedCheckinsPerPerson)
  }
}
