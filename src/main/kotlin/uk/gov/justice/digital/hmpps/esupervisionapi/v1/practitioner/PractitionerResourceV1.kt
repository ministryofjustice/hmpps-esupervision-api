package uk.gov.justice.digital.hmpps.esupervisionapi.v1.practitioner

import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerResource

@RestController
@RequestMapping("/v1/practitioners", produces = [APPLICATION_JSON_VALUE])
class PractitionerResourceV1(private val practitionerResource: PractitionerResource) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @GetMapping("/username/{username}")
  fun getPractitionerByUsername(
    @PathVariable username: ExternalUserId,
  ): ResponseEntity<Practitioner> = practitionerResource.getPractitionerByUsername(username)
}
