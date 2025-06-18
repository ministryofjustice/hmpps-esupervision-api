package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/practitioners", produces = [APPLICATION_JSON_VALUE])
class PractitionerResource(private val practitionerService: PractitionerService) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/{uuid}")
  fun getPractitioner(@PathVariable uuid: UUID): ResponseEntity<Practitioner> {
    val practitioner = practitionerService.getPractitionerByUuid(uuid)
    if (practitioner != null) {
      return ResponseEntity.ok(practitioner)
    }

    return ResponseEntity.notFound().build()
  }
}
