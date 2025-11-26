package uk.gov.justice.digital.hmpps.esupervisionapi.v1.stats

import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.stats.PractitionerRegistrationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.stats.Stats
import uk.gov.justice.digital.hmpps.esupervisionapi.stats.StatsResource

@RestController
@RequestMapping("/v1/stats", produces = [APPLICATION_JSON_VALUE])
class StatsResourceV1(private val statsResource: StatsResource) {
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @GetMapping("/practitioner/registrations")
  fun practitionerRegistrations(): ResponseEntity<List<PractitionerRegistrationInfo>> {
    return statsResource.practitionerRegistrations()
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @GetMapping("/checkins")
  fun checkins(): ResponseEntity<Stats> {
    return statsResource.checkins()
  }
}
