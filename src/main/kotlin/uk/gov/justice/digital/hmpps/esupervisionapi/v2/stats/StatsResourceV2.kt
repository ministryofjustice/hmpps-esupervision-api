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
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import java.lang.invoke.MethodHandles
import java.math.BigDecimal
import java.time.YearMonth

@RestController
@RequestMapping("/v2/stats", produces = ["application/json"])
@Tag(name = "Stats", description = "Aggregated statistics for dashboards and reporting")
class StatsResourceV2(private val service: StatsServiceV2) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Get system statistics",
    description = """
    Returns aggregated system statistics.

    Required Query params:
    - fromMonth=YYYY-MM&toMonth=YYYY-MM (where fromMonth inclusive, toMonth exclusive)
    """,
  )
  @ApiResponse(responseCode = "200", description = "Stats returned successfully")
  @GetMapping
  fun getStats(
    @RequestParam(required = true) fromMonth: String,
    @RequestParam(required = true) toMonth: String,
  ): ResponseEntity<StatsResponse> {
    val from = parseYearMonth(fromMonth).atDay(1)
    val to = parseYearMonth(toMonth).atDay(1)
    if (!from.isBefore(to)) {
      throw BadArgumentException("fromMonth must be < toMonth")
    }
    val result = service.getStatsForMonths(from, to)

    logger.info("Retrieved system stats for month range ({}, {})", from, to)

    return ResponseEntity.ok(
      StatsResponse(
        total = result.total.toStatsBlock(),
        providers = result.providers.map { it.toProviderBlock() },
      ),
    )
  }

  private fun parseYearMonth(value: String): YearMonth = try {
    YearMonth.parse(value) // expects YYYY-MM
  } catch (_: Exception) {
    throw BadArgumentException("Invalid month format '$value' (expected YYYY-MM)")
  }

  companion object {
    private val logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
  }
}

data class StatsResponse(
  val total: StatsBlock,
  val providers: List<ProviderStatsBlock>,
)

data class StatsBlock(
  val totalSignedUp: Long,
  val totalActiveUsers: Long,
  val totalInactiveUsers: Long,
  val signedUp: Long,
  val activeUsers: Long,
  val inactiveUsers: Long,
  val completedCheckins: Long,
  val notCompletedOnTime: Long,
  val avgHoursToComplete: Double,
  val avgCompletedCheckinsPerPerson: Double,
  val pctTotalActiveUsers: Double,
  val pctTotalInactiveUsers: Double,
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
  val totalActiveUsers: Long,
  val totalInactiveUsers: Long,
  val signedUp: Long,
  val activeUsers: Long,
  val inactiveUsers: Long,
  val completedCheckins: Long,
  val notCompletedOnTime: Long,
  val avgHoursToComplete: Double,
  val avgCompletedCheckinsPerPerson: Double,
  val pctTotalActiveUsers: Double,
  val pctTotalInactiveUsers: Double,
  val pctActiveUsers: Double,
  val pctInactiveUsers: Double,
  val pctCompletedCheckins: Double,
  val pctExpiredCheckins: Double,
  val pctSignedUpOfTotal: Double,
  val updatedAt: String,
)

private fun StatsTotalsDto.toStatsBlock() = StatsBlock(
  totalSignedUp = totalSignedUp,
  totalActiveUsers = totalActiveUsers,
  totalInactiveUsers = totalInactiveUsers,
  signedUp = signedUp,
  activeUsers = activeUsers,
  inactiveUsers = inactiveUsers,
  completedCheckins = completedCheckins,
  notCompletedOnTime = notCompletedOnTime,
  avgHoursToComplete = avgHoursToComplete,
  avgCompletedCheckinsPerPerson = avgCompletedCheckinsPerPerson,
  pctTotalActiveUsers = pctTotalActiveUsers,
  pctTotalInactiveUsers = pctTotalInactiveUsers,
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
  totalActiveUsers = totalActiveUsers,
  totalInactiveUsers = totalInactiveUsers,
  signedUp = signedUp,
  activeUsers = activeUsers,
  inactiveUsers = inactiveUsers,
  completedCheckins = completedCheckins,
  notCompletedOnTime = notCompletedOnTime,
  avgHoursToComplete = avgHoursToComplete,
  avgCompletedCheckinsPerPerson = avgCompletedCheckinsPerPerson,
  pctTotalActiveUsers = pctTotalActiveUsers,
  pctTotalInactiveUsers = pctTotalInactiveUsers,
  pctActiveUsers = pctActiveUsers,
  pctInactiveUsers = pctInactiveUsers,
  pctCompletedCheckins = pctCompletedCheckins,
  pctExpiredCheckins = pctExpiredCheckins,
  pctSignedUpOfTotal = pctSignedUpOfTotal,
  updatedAt = updatedAt.toString(),
)
