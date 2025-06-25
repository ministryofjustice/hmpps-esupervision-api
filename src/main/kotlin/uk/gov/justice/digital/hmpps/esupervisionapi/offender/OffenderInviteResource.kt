package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.UploadLocationResponse
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping("/offender_setup", produces = ["application/json"])
class OffenderInviteResource(
  private val offenderSetupService: OffenderSetupService,
  private val s3UploadService: S3UploadService,
) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(
    summary = "Start the setup process for an offender",
    description = """To be called on behalf the practitioner.
    Once a photo is uploaded and personal details confirmed, the practitioner will be able to schedule "checkins." """,
  )
  @PostMapping
  fun startSetup(@RequestBody @Valid offenderInfo: OffenderInfo): ResponseEntity<OffenderSetupDto> {
    val setup = offenderSetupService.startOffenderSetup(offenderInfo)
    return ResponseEntity.ok(
      setup,
    )
  }

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
  ): ResponseEntity<UploadLocationResponse> {
    val supportedContentTypes = setOf("image/jpeg", "image/jpg", "image/png")
    if (!supportedContentTypes.contains(contentType)) {
      return ResponseEntity.badRequest().body(
        UploadLocationResponse(
          locationInfo = null,
          errorMessage = "supported content types: $supportedContentTypes",
        ),
      )
    }
    val setup = offenderSetupService.findSetupByUuid(uuid)
    if (setup.isEmpty) {
      return ResponseEntity.badRequest().body(
        UploadLocationResponse(locationInfo = null, errorMessage = "No invite found with uuid $uuid"),
      )
    }
    if (setup.get().offender.status != OffenderStatus.INITIAL) {
      throw BadArgumentException("setup process already completed or cancelled")
    }

    val duration = Duration.ofMinutes(5)
    val url = s3UploadService.generatePresignedUploadUrl(setup.get(), contentType, duration)
    LOG.debug("generated invite photo upload url: {}", url)
    return ResponseEntity.ok(
      UploadLocationResponse(
        locationInfo = LocationInfo(url, contentType, duration.toString()),
      ),
    )
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @PostMapping("/{uuid}/complete")
  fun completeSetup(@PathVariable uuid: UUID): ResponseEntity<OffenderDto> {
    val setup = offenderSetupService.findSetupByUuid(uuid)
    if (setup.isEmpty) {
      throw BadArgumentException("No setup found for uuid=$uuid")
    }

    val offender = offenderSetupService.completeOffenderSetup(uuid)
    return ResponseEntity.ok(offender)
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @PostMapping("/{uuid}/terminate")
  fun terminateSetup(@PathVariable uuid: UUID): ResponseEntity<OffenderDto> {
    val offender = offenderSetupService.terminateOffenderSetup(uuid)
    return ResponseEntity.ok(offender)
  }

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(this::class.java)
  }
}
