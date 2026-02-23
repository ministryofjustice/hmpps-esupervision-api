package uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@RestController
@RequestMapping("/v2/stats", produces = ["application/json"])
@Tag(name = "Stats", description = "Aggregated statistics for dashboards and reporting")
class StatsResourceV2(private val service: StatsServiceV2) {

  private val logger = LoggerFactory.getLogger(StatsResourceV2::class.java)

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Get system statistics",
    description =
    "Returns aggregated system statistics, for example number of active offenders, number of late checkins etc.",
  )
  @ApiResponse(responseCode = "200", description = "Stats returned successfully")
  @GetMapping
  fun getStats(): ResponseEntity<StatsResponse> {
    val result = service.getStats()

    logger.info("Retrieved system stats")

    return ResponseEntity.ok(
      StatsResponse(
        total = result.total.toStatsBlock(),
        pdus = result.pdus.map { it.toPduBlock() },
      ),
    )
  }
}

data class StatsResponse(
  val total: StatsBlock,
  val pdus: List<PduStatsBlock>,
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

data class PduStatsBlock(
  val pduCode: String,
  val pduDescription: String?,
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

private fun StatsPduDto.toPduBlock() = PduStatsBlock(
  pduCode = pduCode,
  pduDescription = pduDescription,
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
