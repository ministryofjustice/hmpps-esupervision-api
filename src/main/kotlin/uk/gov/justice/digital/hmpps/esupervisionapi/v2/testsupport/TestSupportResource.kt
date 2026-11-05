package uk.gov.justice.digital.hmpps.esupervisionapi.v2.testsupport

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Endpoints for the e2e tests to tidy up after themselves. Dev only: the beans are created only when
 * `app.test-support.enabled` is on and `app.env` is DEV (see [TEST_SUPPORT_ENABLED]), so everywhere
 * else these paths 404 even with a valid token.
 */
@RestController
@RequestMapping("/v2/test-support", produces = ["application/json"])
@ConditionalOnExpression(TEST_SUPPORT_ENABLED)
@Tag(name = "Test Support", description = "Clean-up for the e2e tests (dev only)")
class TestSupportResource(
  private val offenderCleanupService: OffenderCleanupService,
) {

  @DeleteMapping("/offenders/crn/{crn}")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Delete a person on probation whose case has been deleted in NDelius",
    description = """Deletes the person and everything held for them - check-ins, setups, event logs,
      notifications, outbox items, audit rows - in one transaction, then their photos and videos in S3.
      Refused while NDelius still knows the CRN, so delete the case in NDelius first.""",
  )
  @ApiResponse(responseCode = "200", description = "Deleted; the body has the rows deleted per table")
  @ApiResponse(responseCode = "400", description = "Not a valid CRN")
  @ApiResponse(responseCode = "404", description = "No person on probation with this CRN")
  @ApiResponse(responseCode = "409", description = "NDelius still knows this CRN; nothing was deleted")
  @ApiResponse(responseCode = "503", description = "NDelius could not be checked, or the app kept the person locked; nothing was deleted")
  fun deleteOffender(
    @Parameter(description = "Case Reference Number", required = true) @PathVariable crn: String,
  ): OffenderCleanupResponse = offenderCleanupService.deleteOffenderNotInDelius(crn)
}
