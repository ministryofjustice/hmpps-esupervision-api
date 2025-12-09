package uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderInfoV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupV2Dto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Dto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto.UploadLocationResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.time.Duration
import java.util.UUID

/** V2 Offender Setup Resource REST endpoints for V2 registration/setup workflow */
@RestController
@RequestMapping("/v2/offender_setup", produces = ["application/json"])
@Tag(name = "V2 Offender Setup", description = "V2 offender registration/setup endpoints")
class OffenderSetupV2Resource(
  private val offenderSetupService: OffenderSetupV2Service,
  private val s3UploadService: S3UploadService,
) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Start V2 offender setup process",
    description =
    """Start the setup (registration) process for a V2 offender.
      Practitioner initiates setup by providing CRN and schedule details.
      V2 does not store PII - only CRN is stored.
      Once photo is uploaded, practitioner can complete the setup to enable check-ins.""",
  )
  @PostMapping
  fun startSetup(
    @RequestBody @Valid offenderInfo: OffenderInfoV2,
    bindingResult: BindingResult,
  ): ResponseEntity<OffenderSetupV2Dto> {
    if (bindingResult.hasErrors()) {
      val errors = bindingResult.fieldErrors.associateBy({ it.field }, { it.defaultMessage })
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, errors.toString())
    }

    val setup = offenderSetupService.startOffenderSetup(offenderInfo)
    LOGGER.info("Started V2 setup: setupUuid={}, crn={}", setup.uuid, offenderInfo.crn)

    return ResponseEntity.ok(setup)
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Get photo upload location for V2 setup",
    description =
    """Request a presigned S3 URL for uploading the offender's photo.
      The returned URL expires after 5 minutes.
      To upload the image, client must use PUT method with Content-Type: image/jpeg.""",
  )
  @GetMapping("/{uuid}/upload_location")
  fun setupPhotoLocation(
    @PathVariable uuid: UUID,
  ): ResponseEntity<UploadLocationResponse> {
    val setup = offenderSetupService.findSetupByUuid(uuid)
    if (setup.isEmpty) {
      return ResponseEntity.badRequest()
        .body(
          UploadLocationResponse(
            locationInfo = null,
            errorMessage = "No setup found with uuid $uuid",
          ),
        )
    }

    if (setup.get().offender.status != OffenderStatus.INITIAL) {
      throw BadArgumentException("Setup process already completed or cancelled")
    }

    // Generate presigned URL for photo upload
    val duration = Duration.ofMinutes(5)
    val url = s3UploadService.generatePresignedUploadUrl(setup.get(), PHOTO_CONTENT_TYPE, duration)

    LOGGER.debug("Generated V2 setup photo upload URL for setup={}", uuid)

    return ResponseEntity.ok(
      UploadLocationResponse(
        locationInfo = LocationInfo(url, PHOTO_CONTENT_TYPE, duration.toString()),
      ),
    )
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/{uuid}/proxy/photo")
  @Operation(
    summary = "Get photo proxy URL",
    description = "Returns presigned S3 URL for viewing setup photo",
  )
  @ApiResponse(responseCode = "200", description = "Photo URL")
  @ApiResponse(responseCode = "404", description = "Photo not found")
  fun getPhotoProxyUrl(
    @Parameter(description = "Setup UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<Map<String, String>> {
    val url = offenderSetupService.getPhotoProxyUrl(uuid)
    return ResponseEntity.ok(mapOf("url" to url.toString()))
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Complete V2 offender setup",
    description =
    """Complete the setup process for a V2 offender.
      Verifies that photo has been uploaded, changes status to VERIFIED,
      sends notifications, and creates first check-in if due today.""",
  )
  @PostMapping("/{uuid}/complete")
  fun completeSetup(@PathVariable uuid: UUID): ResponseEntity<OffenderV2Dto> {
    val setup = offenderSetupService.findSetupByUuid(uuid)
    if (setup.isEmpty) {
      throw BadArgumentException("No setup found for uuid=$uuid")
    }

    val offender = offenderSetupService.completeOffenderSetup(uuid)
    LOGGER.info(
      "Completed V2 setup: setupUuid={}, offenderUuid={}, crn={}",
      uuid,
      offender.uuid,
      offender.crn,
    )

    return ResponseEntity.ok(offender)
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Terminate V2 offender setup",
    description =
    """Cancel/terminate the setup process for a V2 offender.
      Marks offender as INACTIVE and deletes the setup record.
      Can only be called if setup has not been completed.""",
  )
  @PostMapping("/{uuid}/terminate")
  fun terminateSetup(@PathVariable uuid: UUID): ResponseEntity<OffenderV2Dto> {
    val offender = offenderSetupService.terminateOffenderSetup(uuid)
    LOGGER.info(
      "Terminated V2 setup: setupUuid={}, offenderUuid={}, crn={}",
      uuid,
      offender.uuid,
      offender.crn,
    )

    return ResponseEntity.ok(offender)
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(OffenderSetupV2Resource::class.java)
    private const val PHOTO_CONTENT_TYPE = "image/jpeg"
  }
}
