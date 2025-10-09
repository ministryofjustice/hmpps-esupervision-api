package uk.gov.justice.digital.hmpps.esupervisionapi.stats

import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/stats", produces = [APPLICATION_JSON_VALUE])
class StatsResource(private val statsService: StatsService) {
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @GetMapping("/practitioner/registrations")
  fun practitionerRegistrations(): ResponseEntity<List<PractitionerRegistrationInfo>> {
    val registrations = statsService.practitionerRegistrations()
    return ResponseEntity.ok(registrations)
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @GetMapping("/checkins")
  fun checkins(): ResponseEntity<Stats> {
    val checkins = statsService.checkinStats()
    return ResponseEntity.ok(checkins)
  }
}
