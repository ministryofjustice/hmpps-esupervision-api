package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.UploadLocationResponse
import java.util.UUID

@RestController
@RequestMapping(path = ["/offender_checkins"])
class OffenderCheckinResource {

  @GetMapping("/{uuid}")
  @Tag(name = "offender")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun getCheckin(@PathVariable uuid: UUID): ResponseEntity<OffenderCheckinDto> {
    TODO("not implemented yet")
  }

  @PostMapping("/{uuid}/upload_location")
  @Tag(name = "offender")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun uploadLocation(
    @PathVariable uuid: UUID,
    @RequestParam(name = "content-type", required = true) contentType: String,
  ): ResponseEntity<UploadLocationResponse> {
    TODO("not implemented yet")
  }

  @PostMapping("/{uuid}/submit")
  @Tag(name = "offender")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun submitCheckin() {
    TODO("not implemented yet")
  }
}
