package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException

@RestController
@RequestMapping("/practitioners", produces = [APPLICATION_JSON_VALUE])
class PractitionerResource(private val practitionerService: PractitionerService) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @PostMapping
  fun createPractitioner(@RequestBody @Valid createRequest: PractitionerDto): ResponseEntity<Object> {
    // TODO: validate in a spring-y way
    val practitioner = Practitioner(
      uuid = createRequest.uuid,
      firstName = createRequest.firstName,
      lastName = createRequest.lastName,
      email = createRequest.email,
      phoneNumber = createRequest.phoneNumber,
    )
    try {
      practitionerService.createPractitioner(practitioner)
    } catch (e: DataIntegrityViolationException) {
      throw BadArgumentException("entity with given uuid already exists")
    }

    val practitionerUrl = ServletUriComponentsBuilder.fromCurrentRequestUri()
      .path("/{uuid}")
      .buildAndExpand(practitioner.uuid)
      .toUri()

    return ResponseEntity.created(practitionerUrl).build()
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @GetMapping("/{uuid}")
  fun getPractitioner(@PathVariable uuid: String): ResponseEntity<Practitioner> {
    val practitioner = practitionerService.getPractitionerByUuid(uuid)
    if (practitioner != null) {
      return ResponseEntity.ok(practitioner)
    }

    return ResponseEntity.notFound().build()
  }
}
