package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/practitioners", produces = [APPLICATION_JSON_VALUE])
class PractitionerResource(private val roRepository: NewPractitionerRepository) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @GetMapping("/username/{username}")
  fun getPractitionerByUsername(@PathVariable username: ExternalUserId): ResponseEntity<NewPractitioner> {
    val practitioner = roRepository.findById(username)
    if (practitioner == null) {
      return ResponseEntity.notFound().build()
    } else {
      return ResponseEntity.ok(practitioner)
    }
  }
}
