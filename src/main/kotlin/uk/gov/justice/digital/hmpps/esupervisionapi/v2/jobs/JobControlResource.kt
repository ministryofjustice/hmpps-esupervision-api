package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2/jobs")
@Profile("local")
@Tag(name = "Job Control", description = "Endpoints to manually trigger jobs (Local only)")
class JobControlResource(
  private val v2CheckinCreationJob: V2CheckinCreationJob,
) {

  @PostMapping("/checkin-creation")
  @PreAuthorize("permitAll()")
  @Operation(summary = "Trigger V2 Checkin Creation Job")
  fun triggerCheckinCreation() {
    v2CheckinCreationJob.process()
  }
}
