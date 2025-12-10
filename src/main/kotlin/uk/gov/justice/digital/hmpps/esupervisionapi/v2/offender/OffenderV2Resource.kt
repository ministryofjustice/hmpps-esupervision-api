package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.util.UUID

@RestController
@RequestMapping("/v2/offenders", produces = ["application/json"])
@Tag(name = "V2 Offenders", description = "V2 offender endpoints")
class OffenderV2Resource(
  private val offenderRepository: OffenderV2Repository,
  private val s3UploadService: S3UploadService,
) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Get offender by CRN",
    description = "Returns offender registration details (no PII). Returns 404 if not found.",
  )
  @ApiResponse(responseCode = "200", description = "Offender found")
  @ApiResponse(responseCode = "404", description = "Offender not found")
  @GetMapping("/crn/{crn}")
  fun getOffenderByCrn(
    @Parameter(description = "Case Reference Number", required = true) @PathVariable crn: String,
  ): ResponseEntity<OffenderSummaryDto> {
    val offender = offenderRepository.findByCrn(crn.trim().uppercase()).orElse(null)
    if (offender == null) {
      LOGGER.info("Offender not found for crn={}", crn)
      return ResponseEntity.notFound().build()
    }

    LOGGER.info("Found offender by CRN: crn={}, status={}", offender.crn, offender.status)
    return ResponseEntity.ok(offender.toSummaryDto())
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/{uuid}/proxy/photo")
  @Operation(
    summary = "Get photo proxy URL",
    description = "Returns presigned S3 URL for viewing offender's setup photo",
  )
  @ApiResponse(responseCode = "200", description = "Photo URL")
  @ApiResponse(responseCode = "404", description = "Offender or photo not found")
  fun getPhotoProxyUrl(
    @Parameter(description = "Offender UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<Map<String, String>> {
    val offender = offenderRepository.findByUuid(uuid).orElse(null)
    if (offender == null) {
      LOGGER.warn("Photo proxy request failed: offender not found for uuid={}", uuid)
      return ResponseEntity.notFound().build()
    }

    if (offender.status != OffenderStatus.VERIFIED) {
      LOGGER.warn("Photo proxy request failed: offender uuid={} status={} is not VERIFIED", uuid, offender.status)
      return ResponseEntity.notFound().build()
    }

    val url = s3UploadService.getOffenderPhoto(offender)
    if (url == null) {
      LOGGER.warn("Photo proxy request failed: photo not found in S3 for offender uuid={}", uuid)
      return ResponseEntity.notFound().build()
    }

    LOGGER.debug("Returning photo proxy URL for offender uuid={}", uuid)
    return ResponseEntity.ok(mapOf("url" to url.toString()))
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(OffenderV2Resource::class.java)
  }
}

/** Simple DTO for offender lookup - no PII */
data class OffenderSummaryDto(
  val uuid: UUID,
  val crn: String,
  val status: OffenderStatus,
  val firstCheckin: java.time.LocalDate,
  val checkinInterval: uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval,
)

private fun uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2.toSummaryDto() = OffenderSummaryDto(
  uuid = uuid,
  crn = crn,
  status = status,
  firstCheckin = firstCheckin,
  checkinInterval = uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval.fromDuration(checkinInterval),
)
