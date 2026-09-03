package uk.gov.justice.digital.hmpps.esupervisionapi.v2.practitioner

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient

@RestController
@RequestMapping("/v2/practitioners", produces = ["application/json"])
@Tag(name = "V2 Practitioners", description = "V2 practitioner endpoints")
class PractitionerResource(
  private val ndiliusApiClient: INdiliusApiClient,
) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Get alert count by username",
    description = "Returns the number of NDelius alerts for a practitioner, straight from NDelius's GET /user/{username}/alerts.",
  )
  @ApiResponse(responseCode = "200", description = "Alert count found")
  @ApiResponse(responseCode = "404", description = "Username not found")
  @GetMapping("/{username}/alerts")
  fun getAlertsByUsername(
    @Parameter(description = "NDelius username", required = true) @PathVariable username: String,
  ): ResponseEntity<AlertsSummary> {
    val trimmedUsername = username.trim()
    val count = ndiliusApiClient.getAlertCount(trimmedUsername)
    if (count == null) {
      LOGGER.info("Alerts not found for username: {}", trimmedUsername)
      return ResponseEntity.notFound().build()
    }

    LOGGER.info("Retrieved alert count for username: {}", trimmedUsername)
    return ResponseEntity.ok(AlertsSummary(count = count))
  }

  companion object {
    private val LOGGER = logger<PractitionerResource>()
  }
}

data class AlertsSummary(
  @field:Schema(description = "Number of alerts", required = true, example = "3")
  val count: Int,
)
