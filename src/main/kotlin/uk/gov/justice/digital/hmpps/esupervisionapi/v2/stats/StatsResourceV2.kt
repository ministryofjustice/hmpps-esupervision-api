package uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.YearMonth

@RestController
@RequestMapping("/v2/stats", produces = ["application/json"])
@Tag(name = "Stats", description = "Aggregated statistics for dashboards and reporting")
class StatsResourceV2(private val service: StatsServiceV2) {

  private val logger = LoggerFactory.getLogger(StatsResourceV2::class.java)

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Get system statistics",
    description = """
    Returns aggregated system statistics.

    Required Query params:
    - month=YYYY-MM (single month)
    - fromMonth=YYYY-MM&toMonth=YYYY-MM (inclusive range)
    """,
  )
  @ApiResponse(responseCode = "200", description = "Stats returned successfully")
  @GetMapping
  fun getStats(
    @RequestParam(required = false) month: String?,
    @RequestParam(required = false) fromMonth: String?,
    @RequestParam(required = false) toMonth: String?,
  ): ResponseEntity<StatsResponse> {
    if (month != null && (fromMonth != null || toMonth != null)) {
      throw IllegalArgumentException("Provide either month=YYYY-MM OR fromMonth=YYYY-MM&toMonth=YYYY-MM, not both")
    }

    val result = when {
      month != null -> {
        val m = parseYearMonth(month).atDay(1)
        service.getStatsForMonth(m)
      }

      fromMonth != null || toMonth != null -> {
        val from = parseYearMonth(fromMonth ?: toMonth!!).atDay(1)
        val to = parseYearMonth(toMonth ?: fromMonth!!).atDay(1)

        require(!from.isAfter(to)) { "fromMonth must be <= toMonth" }

        service.getStatsForMonths(from, to)
      }

      else -> throw IllegalArgumentException("You must provide month=YYYY-MM or fromMonth=YYYY-MM&toMonth=YYYY-MM")
    }

    logger.info("Retrieved system stats")

    return ResponseEntity.ok(
      StatsResponse(
        total = result.total.toStatsBlock(),
        providers = result.providers.map { it.toProviderBlock() },
      ),
    )
  }

  private fun parseYearMonth(value: String): YearMonth = try {
    YearMonth.parse(value) // expects YYYY-MM
  } catch (e: Exception) {
    throw IllegalArgumentException("Invalid month format '$value' (expected YYYY-MM)")
  }
}

data class StatsResponse(
  val total: StatsBlock,
  val providers: List<ProviderStatsBlock>,
)

data class StatsBlock(
  val totalSignedUp: Long,
  val activeUsers: Long,
  val inactiveUsers: Long,
  val completedCheckins: Long,
  val notCompletedOnTime: Long,
  val avgHoursToComplete: Double,
  val avgCompletedCheckinsPerPerson: Double,
  val pctActiveUsers: Double,
  val pctInactiveUsers: Double,
  val pctCompletedCheckins: Double,
  val pctExpiredCheckins: Double,
  val feedbackTotal: Long,
  val howEasyCounts: Map<String, Long>,
  val howEasyPct: Map<String, BigDecimal>,
  val gettingSupportCounts: Map<String, Long>,
  val gettingSupportPct: Map<String, BigDecimal>,
  val improvementsCounts: Map<String, Long>,
  val improvementsPct: Map<String, BigDecimal>,
  val pctSignedUpOfTotal: Double,
  val updatedAt: String,
)

data class ProviderStatsBlock(
  val providerCode: String,
  val providerDescription: String?,
  val totalSignedUp: Long,
  val activeUsers: Long,
  val inactiveUsers: Long,
  val completedCheckins: Long,
  val notCompletedOnTime: Long,
  val avgHoursToComplete: Double,
  val avgCompletedCheckinsPerPerson: Double,
  val pctActiveUsers: Double,
  val pctInactiveUsers: Double,
  val pctCompletedCheckins: Double,
  val pctExpiredCheckins: Double,
  val pctSignedUpOfTotal: Double,
  val updatedAt: String,
)

private fun StatsTotalsDto.toStatsBlock() = StatsBlock(
  totalSignedUp = totalSignedUp,
  activeUsers = activeUsers,
  inactiveUsers = inactiveUsers,
  completedCheckins = completedCheckins,
  notCompletedOnTime = notCompletedOnTime,
  avgHoursToComplete = avgHoursToComplete,
  avgCompletedCheckinsPerPerson = avgCompletedCheckinsPerPerson,
  pctActiveUsers = pctActiveUsers,
  pctInactiveUsers = pctInactiveUsers,
  pctCompletedCheckins = pctCompletedCheckins,
  pctExpiredCheckins = pctExpiredCheckins,
  feedbackTotal = feedbackTotal,
  howEasyCounts = howEasyCounts,
  howEasyPct = howEasyPct,
  gettingSupportCounts = gettingSupportCounts,
  gettingSupportPct = gettingSupportPct,
  improvementsCounts = improvementsCounts,
  improvementsPct = improvementsPct,
  pctSignedUpOfTotal = pctSignedUpOfTotal,
  updatedAt = updatedAt.toString(),
)

private fun StatsProviderDto.toProviderBlock() = ProviderStatsBlock(
  providerCode = providerCode,
  providerDescription = providerDescription,
  totalSignedUp = totalSignedUp,
  activeUsers = activeUsers,
  inactiveUsers = inactiveUsers,
  completedCheckins = completedCheckins,
  notCompletedOnTime = notCompletedOnTime,
  avgHoursToComplete = avgHoursToComplete,
  avgCompletedCheckinsPerPerson = avgCompletedCheckinsPerPerson,
  pctActiveUsers = pctActiveUsers,
  pctInactiveUsers = pctInactiveUsers,
  pctCompletedCheckins = pctCompletedCheckins,
  pctExpiredCheckins = pctExpiredCheckins,
  pctSignedUpOfTotal = pctSignedUpOfTotal,
  updatedAt = updatedAt.toString(),
)
