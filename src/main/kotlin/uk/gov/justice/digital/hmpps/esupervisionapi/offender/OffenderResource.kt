package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.intoResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.toPagination
import java.util.UUID

@RestController
@RequestMapping("/offenders", produces = ["application/json"])
@Validated
class OffenderResource(
  private val offenderService: OffenderService,
) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(summary = "Returns a collection of offender records")
  @GetMapping
  fun getOffenders(
    @RequestParam(required = true) practitionerUuid: String,
    @Parameter(description = "Zero-based page index")
    @RequestParam(defaultValue = "0") page: Int,
    @RequestParam(defaultValue = "20") @Max(100) size: Int,
  ): ResponseEntity<CollectionDto<OffenderDto>> {
    val pageRequest = PageRequest.of(page, size)
    val offenders = offenderService.getOffenders(practitionerUuid, pageable = pageRequest)
    return ResponseEntity.ok(
      CollectionDto(
        pagination = pageRequest.toPagination(),
        content = offenders.content,
      ),
    )
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(summary = "Returns an offender record")
  @GetMapping("/{uuid}")
  fun getOffender(@PathVariable uuid: UUID): ResponseEntity<OffenderDto> = ResponseEntity.ok(offenderService.getOffender(uuid))

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
  ): ResponseEntity<OffenderDto> = ResponseEntity.ok(offenderService.updateDetails(uuid, details))

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @GetMapping("/{uuid}/upload_location")
  fun getPhotoUploadLocation(@PathVariable uuid: UUID, @RequestParam(name = "content-type") contentType: String): ResponseEntity<LocationInfo> = ResponseEntity.ok(offenderService.photoUploadLocation(uuid, contentType))

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(
    summary = "Stops ",
  )
  @PostMapping("/{uuid}/deactivate")
  fun terminateCheckins(
    @PathVariable uuid: UUID,
    @RequestBody body: DeactivateOffenderCheckinRequest,
    bindingResult: org.springframework.validation.BindingResult,
  ): ResponseEntity<OffenderDto> {
    if (bindingResult.hasErrors()) {
      throw intoResponseStatusException(bindingResult)
    }
    return ResponseEntity.ok(offenderService.cancelCheckins(uuid, body))
  }
}
