package uk.gov.justice.digital.hmpps.esupervisionapi.v1.offender

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.BindingResult
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.DeactivateOffenderCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderDetailsUpdate
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderResource
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocationInfo
import java.util.UUID

@RestController
@RequestMapping("/v1/offenders", produces = ["application/json"])
@Validated
class OffenderResourceV1(
  private val offenderResource: OffenderResource,
) {
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(summary = "Returns a collection of offender records")
  @GetMapping
  fun getOffenders(
    @RequestParam("practitioner", required = true) practitionerId: ExternalUserId,
    @Parameter(description = "Zero-based page index")
    @RequestParam(defaultValue = "0") page: Int,
    @RequestParam(defaultValue = "20") @Max(100) size: Int,
    @RequestParam(required = false) email: String?,
    @RequestParam(required = false, name = "phone_number") phoneNumber: String?,
  ): ResponseEntity<CollectionDto<OffenderDto>> = offenderResource.getOffenders(practitionerId, page, size, email, phoneNumber)

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(summary = "Returns an offender record")
  @GetMapping("/{uuid}")
  fun getOffender(@PathVariable uuid: UUID): ResponseEntity<OffenderDto> = offenderResource.getOffender(uuid)

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(
    summary = "Updates offender details",
    description = """The request body represents the new offender details. All fields need to be set to their desired value.""",
  )
  @PostMapping("/{uuid}/details")
  fun updateDetails(
    @PathVariable uuid: UUID,
    @RequestBody @Valid details: OffenderDetailsUpdate,
  ): ResponseEntity<OffenderDto> = offenderResource.updateDetails(uuid, details)

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @GetMapping("/{uuid}/upload_location")
  fun getPhotoUploadLocation(
    @PathVariable uuid: UUID,
    @RequestParam(name = "content-type") contentType: String,
  ): ResponseEntity<LocationInfo> = offenderResource.getPhotoUploadLocation(uuid, contentType)

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(
    summary = "Stops ",
  )
  @PostMapping("/{uuid}/deactivate")
  fun terminateCheckins(
    @PathVariable uuid: UUID,
    @RequestBody body: DeactivateOffenderCheckinRequest,
    bindingResult: BindingResult,
  ): ResponseEntity<OffenderDto> = offenderResource.terminateCheckins(uuid, body, bindingResult)
}
