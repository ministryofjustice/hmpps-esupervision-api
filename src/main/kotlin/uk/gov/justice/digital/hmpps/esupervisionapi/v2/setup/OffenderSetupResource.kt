package uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto.UploadHashRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto.UploadLocationResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.resolveUploadHash
import java.time.Duration
import java.util.UUID

/** V2 Offender Setup Resource REST endpoints for V2 registration/setup workflow */
@RestController
@RequestMapping("/v2/offender_setup", produces = ["application/json"])
@Tag(name = "V2 Offender Setup", description = "V2 offender registration/setup endpoints")
class OffenderSetupResource(
  private val offenderSetupService: OffenderSetupService,
  private val s3UploadService: S3UploadService,
  private val appConfig: AppConfig,
) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Start V2 offender setup process",
    description = """Start the onboarding process for a V2 offender.
      Practitioner initiates setup by providing CRN and schedule details.
      V2 does not store PII - only CRN is stored.
      Once photo is uploaded, practitioner can complete the setup to enable check-ins.""",
  )
  @PostMapping()
  fun startSetupByCrn(
    @RequestBody @Valid offenderInfo: OffenderInfo,
    bindingResult: BindingResult,
  ): ResponseEntity<OffenderSetupDto> {
    if (bindingResult.hasErrors()) {
      val errors = bindingResult.fieldErrors.associateBy({ it.field }, { it.defaultMessage })
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, errors.toString())
    }
    return ResponseEntity.ok(offenderSetupService.startOffenderSetup(offenderInfo))
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Get photo upload location for V2 setup",
    description =
    """Request a presigned S3 URL for uploading the offender's photo.
      The returned URL expires after 5 minutes.
      To upload the image, client must use PUT method with the specified content-type.""",
  )
  @PostMapping("/{uuid}/upload_location")
  fun setupPhotoLocation(
    @PathVariable uuid: UUID,
    @RequestParam(name = "content-type", required = true) contentType: String,
    @RequestBody(required = false) hashRequest: UploadHashRequest?,
  ): ResponseEntity<UploadLocationResponse> {
    val supportedContentTypes = setOf("image/jpeg", "image/jpg", "image/png")

    if (!supportedContentTypes.contains(contentType)) {
      return ResponseEntity.badRequest()
        .body(
          UploadLocationResponse(
            locationInfo = null,
            errorMessage = "Supported content types: $supportedContentTypes",
          ),
        )
    }

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

    val hash = resolveUploadHash(
      sha256Base64 = hashRequest?.sha256,
      require = appConfig.enabledFeatures.contains(Feature.ESUP_1672_REQUIRE_UPLOAD_CONTENT_HASH),
      slot = "setup-photo",
    )
    LOGGER.info("upload_hash.received endpoint=/v2/offender_setup/upload_location received={}", hash != null)

    val duration = Duration.ofMinutes(5)
    val presigned = s3UploadService.generatePresignedUpload(setup.get(), contentType, duration, hash)

    LOGGER.debug("Generated V2 setup photo upload URL for setup={}", uuid)

    return ResponseEntity.ok(
      UploadLocationResponse(
        locationInfo = LocationInfo(
          url = presigned.url,
          contentType = contentType,
          duration = duration.toString(),
          requiredHeaders = presigned.requiredHeaders.takeIf { it.isNotEmpty() },
        ),
      ),
    )
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
  fun completeSetup(@PathVariable uuid: UUID): ResponseEntity<OffenderDto> {
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
  fun terminateSetup(@PathVariable uuid: UUID): ResponseEntity<OffenderDto> {
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
    private val LOGGER = LoggerFactory.getLogger(OffenderSetupResource::class.java)
  }
}
