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
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats.StatsWithPercentages

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
    val stats = service.getStats()
    logger.info("Retrieved system stats")
    return ResponseEntity.ok(stats.toResponse())
  }
}

/** Response DTO with percentages as decimals (0.0–1.0) */
data class StatsResponse(
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
  val updatedAt: String,
)

/** Map StatsWithPercentages from service to response DTO */
private fun StatsWithPercentages.toResponse() = StatsResponse(
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
  updatedAt = updatedAt.toString(),
)
