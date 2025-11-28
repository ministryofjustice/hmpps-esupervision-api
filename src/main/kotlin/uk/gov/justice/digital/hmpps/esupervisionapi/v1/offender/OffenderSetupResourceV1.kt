package uk.gov.justice.digital.hmpps.esupervisionapi.v1.offender

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetupDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetupResource
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.UploadLocationResponse
import java.util.UUID

@RestController
@RequestMapping("/v1/offender_setup", produces = ["application/json"])
class OffenderSetupResourceV1(
  private val offenderSetupResource: OffenderSetupResource,
) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(
    summary = "Start the setup process for an offender",
    description = """To be called on behalf the practitioner.
    Once a photo is uploaded and personal details confirmed, the practitioner will be able to schedule "checkins." """,
  )
  @PostMapping
  fun startSetup(
    @RequestBody @Valid offenderInfo: OffenderInfo,
    bindingResult: BindingResult,
  ): ResponseEntity<OffenderSetupDto> = offenderSetupResource.startSetup(offenderInfo, bindingResult)

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(
    summary = "Request a photo upload location (an URL)",
    description = """The returned URL expires after 5 minutes.
      To upload the image, the client must use `PUT` method and use the same content-type 
      as the one passed to this endpoint.""",
  )
  @PostMapping("/{uuid}/upload_location")
  fun setupPhotoLocation(
    @PathVariable uuid: UUID,
    @RequestParam(name = "content-type", required = true) contentType: String,
  ): ResponseEntity<UploadLocationResponse> = offenderSetupResource.setupPhotoLocation(uuid, contentType)

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @PostMapping("/{uuid}/complete")
  fun completeSetup(
    @PathVariable uuid: UUID,
  ): ResponseEntity<OffenderDto> = offenderSetupResource.completeSetup(uuid)

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @PostMapping("/{uuid}/terminate")
  fun terminateSetup(
    @PathVariable uuid: UUID,
  ): ResponseEntity<OffenderDto> = offenderSetupResource.terminateSetup(uuid)
}
