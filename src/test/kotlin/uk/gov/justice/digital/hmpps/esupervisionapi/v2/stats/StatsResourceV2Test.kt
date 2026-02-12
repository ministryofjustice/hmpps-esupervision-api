package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsResourceV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsServiceV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsWithPercentages
import java.time.Instant

class StatsResourceV2Test {

  private val service: StatsServiceV2 = mock()
  private val resource = StatsResourceV2(service)
  private val mapper = ObjectMapper()

  @Test
  fun `getStats returns expected StatsResponse`() {
    val howEasyCounts: JsonNode =
      mapper.valueToTree(
        mapOf(
          "veryEasy" to 1L,
          "difficult" to 1L,
          "notAnswered" to 2L,
        ),
      )

    val howEasyPct: JsonNode =
      mapper.valueToTree(
        mapOf(
          "veryEasy" to 0.25,
          "difficult" to 0.25,
        ),
      )

    val gettingSupportCounts: JsonNode =
      mapper.valueToTree(
        mapOf(
          "no" to 1L,
          "yes" to 2L,
          "notAnswered" to 1L,
        ),
      )

    val gettingSupportPct: JsonNode =
      mapper.valueToTree(
        mapOf(
          "no" to 0.3333,
          "yes" to 0.6667,
        ),
      )

    val improvementsCounts: JsonNode =
      mapper.valueToTree(
        mapOf(
          "gettingHelp" to 1L,
          "checkInQuestions" to 2L,
          "notAnswered" to 1L,
        ),
      )

    val improvementsPct: JsonNode =
      mapper.valueToTree(
        mapOf(
          "gettingHelp" to 0.5,
          "checkInQuestions" to 1.0,
        ),
      )

    val statsWithPercentages =
      StatsWithPercentages(
        totalSignedUp = 10,
        activeUsers = 7,
        inactiveUsers = 3,
        completedCheckins = 20,
        notCompletedOnTime = 2,
        avgHoursToComplete = 5.5,
        avgCompletedCheckinsPerPerson = 2.86,
        updatedAt = Instant.parse("2026-01-28T12:02:00.020175Z"),
        pctActiveUsers = 0.7,
        pctInactiveUsers = 0.3,
        pctCompletedCheckins = 1.0,
        pctExpiredCheckins = 0.1,
        feedbackTotal = 10,
        howEasyCounts = howEasyCounts,
        howEasyPct = howEasyPct,
        gettingSupportCounts = gettingSupportCounts,
        gettingSupportPct = gettingSupportPct,
        improvementsCounts = improvementsCounts,
        improvementsPct = improvementsPct,
      )

    whenever(service.getStats()).thenReturn(statsWithPercentages)

    val response = resource.getStats()
    val body = response.body!!

    assertEquals(HttpStatus.OK, response.statusCode)

    assertEquals(10, body.totalSignedUp)
    assertEquals(7, body.activeUsers)
    assertEquals(3, body.inactiveUsers)
    assertEquals(20, body.completedCheckins)
    assertEquals(2, body.notCompletedOnTime)
    assertEquals(5.5, body.avgHoursToComplete)
    assertEquals(2.86, body.avgCompletedCheckinsPerPerson)
    assertEquals("2026-01-28T12:02:00.020175Z", body.updatedAt)
    assertEquals(0.7, body.pctActiveUsers)
    assertEquals(0.3, body.pctInactiveUsers)
    assertEquals(1.0, body.pctCompletedCheckins)
    assertEquals(0.1, body.pctExpiredCheckins)

    // Feedback stats
    assertEquals(10, body.feedbackTotal)

    assertEquals(1L, body.howEasyCounts["veryEasy"].asLong())
    assertEquals(1L, body.howEasyCounts["difficult"].asLong())
    assertEquals(2L, body.howEasyCounts["notAnswered"].asLong())
    assertEquals(0.25, body.howEasyPct["veryEasy"].asDouble())
    assertEquals(0.25, body.howEasyPct["difficult"].asDouble())
    assertEquals(true, body.howEasyPct["notAnswered"] == null) // asserting this is not present

    assertEquals(2L, body.gettingSupportCounts["yes"].asLong())
    assertEquals(1L, body.gettingSupportCounts["no"].asLong())
    assertEquals(1L, body.gettingSupportCounts["notAnswered"].asLong())
    assertEquals(0.6667, body.gettingSupportPct["yes"].asDouble())
    assertEquals(0.3333, body.gettingSupportPct["no"].asDouble())
    assertEquals(true, body.gettingSupportPct["notAnswered"] == null)

    assertEquals(2L, body.improvementsCounts["checkInQuestions"].asLong())
    assertEquals(1L, body.improvementsCounts["gettingHelp"].asLong())
    assertEquals(1L, body.improvementsCounts["notAnswered"].asLong())
    assertEquals(1.0, body.improvementsPct["checkInQuestions"].asDouble())
    assertEquals(0.5, body.improvementsPct["gettingHelp"].asDouble())
    assertEquals(true, body.improvementsPct["notAnswered"] == null)
  }
}
