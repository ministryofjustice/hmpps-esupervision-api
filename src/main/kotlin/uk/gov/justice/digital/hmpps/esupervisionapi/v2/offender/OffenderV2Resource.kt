package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto.UploadLocationResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/v2/offenders", produces = ["application/json"])
@Tag(name = "V2 Offenders", description = "V2 offender endpoints")
class OffenderV2Resource(
  private val offenderRepository: OffenderV2Repository,
  private val s3UploadService: S3UploadService,
  private val clock: Clock,
  private val checkinCreationService: CheckinCreationService,
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
    return ResponseEntity.ok(offender.toSummaryDto(getOffenderPhotoUrl(offender)))
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
      LOGGER.info("Photo proxy request failed: offender not found for uuid={}", uuid)
      return ResponseEntity.notFound().build()
    }

    val photoUrl = getOffenderPhotoUrl(offender)
    if (photoUrl == null) {
      LOGGER.info("Photo proxy request failed: offender uuid={} status={}", uuid, offender.status)
      return ResponseEntity.notFound().build()
    }

    LOGGER.debug("Returning photo proxy URL for offender uuid={}", uuid)
    return ResponseEntity.ok(mapOf("url" to photoUrl))
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Get photo upload location for offender",
    description = """Request a presigned S3 URL for uploading/updating the offender's reference photo.
      The returned URL expires after 5 minutes.
      Only VERIFIED offenders can have their photo updated.
      To upload the image, client must use PUT method with the specified content-type.""",
  )
  @ApiResponse(responseCode = "200", description = "Upload URL generated")
  @ApiResponse(responseCode = "400", description = "Invalid content type or offender not VERIFIED")
  @ApiResponse(responseCode = "404", description = "Offender not found")
  @PostMapping("/{uuid}/upload_location")
  fun getPhotoUploadLocation(
    @Parameter(description = "Offender UUID", required = true) @PathVariable uuid: UUID,
    @Parameter(description = "Content type of the image", required = true)
    @RequestParam(name = "content-type") contentType: String,
  ): ResponseEntity<UploadLocationResponse> {
    val supportedContentTypes = setOf("image/jpeg", "image/jpg", "image/png")

    if (!supportedContentTypes.contains(contentType)) {
      return ResponseEntity.badRequest().body(
        UploadLocationResponse(
          locationInfo = null,
          errorMessage = "Supported content types: $supportedContentTypes",
        ),
      )
    }

    val offender = offenderRepository.findByUuid(uuid).orElse(null)
    if (offender == null) {
      LOGGER.warn("Upload location request failed: offender not found for uuid={}", uuid)
      return ResponseEntity.notFound().build()
    }

    if (offender.status != OffenderStatus.VERIFIED) {
      return ResponseEntity.badRequest().body(
        UploadLocationResponse(
          locationInfo = null,
          errorMessage = "Cannot update photo for offender with status ${offender.status}. Only VERIFIED offenders can have their photo updated.",
        ),
      )
    }

    val duration = Duration.ofMinutes(5)
    val url = s3UploadService.generatePresignedUploadUrl(offender, contentType, duration)

    LOGGER.info("Generated photo upload URL for offender uuid={}, crn={}", uuid, offender.crn)

    return ResponseEntity.ok(
      UploadLocationResponse(
        locationInfo = LocationInfo(url, contentType, duration.toString()),
      ),
    )
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Deactivate offender",
    description = """Deactivates a VERIFIED offender, changing their status to INACTIVE.
      This prevents further check-ins from being created or submitted.
      Only VERIFIED offenders can be deactivated.""",
  )
  @ApiResponse(responseCode = "200", description = "Offender deactivated")
  @ApiResponse(responseCode = "400", description = "Offender not in VERIFIED status")
  @ApiResponse(responseCode = "404", description = "Offender not found")
  @PostMapping("/{uuid}/deactivate")
  fun deactivateOffender(
    @Parameter(description = "Offender UUID", required = true) @PathVariable uuid: UUID,
    @Valid @RequestBody request: DeactivateOffenderRequest,
  ): ResponseEntity<OffenderSummaryDto> {
    val offender = offenderRepository.findByUuid(uuid).orElse(null)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Offender not found: $uuid")

    if (offender.status != OffenderStatus.VERIFIED) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Cannot deactivate offender with status ${offender.status}. Only VERIFIED offenders can be deactivated.",
      )
    }

    offender.status = OffenderStatus.INACTIVE
    offender.updatedAt = clock.instant()
    val saved = offenderRepository.save(offender)

    LOGGER.info(
      "Deactivated offender: uuid={}, crn={}, requestedBy={}, reason={}",
      uuid,
      offender.crn,
      request.requestedBy,
      request.reason,
    )

    return ResponseEntity.ok(saved.toSummaryDto())
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Reactivate offender",
    description = """Reactivates an INACTIVE offender, changing their status back to VERIFIED.
      This allows check-ins to be created and submitted again.
      Only INACTIVE offenders can be reactivated.
      Note: V1 does not support reactivation, but V2 requires it due to unique CRN constraint.""",
  )
  @ApiResponse(responseCode = "200", description = "Offender reactivated")
  @ApiResponse(responseCode = "400", description = "Offender not in INACTIVE status")
  @ApiResponse(responseCode = "404", description = "Offender not found")
  @PostMapping("/{uuid}/reactivate")
  fun reactivateOffender(
    @Parameter(description = "Offender UUID", required = true) @PathVariable uuid: UUID,
    @Valid @RequestBody request: ReactivateOffenderRequest,
  ): ResponseEntity<OffenderSummaryDto> {
    val offender = offenderRepository.findByUuid(uuid).orElse(null)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Offender not found: $uuid")

    if (offender.status != OffenderStatus.INACTIVE) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Cannot reactivate offender with status ${offender.status}. Only INACTIVE offenders can be reactivated.",
      )
    }

    offender.status = OffenderStatus.VERIFIED
    offender.updatedAt = clock.instant()
    val saved = offenderRepository.save(offender)

    LOGGER.info(
      "Reactivated offender: uuid={}, crn={}, requestedBy={}, reason={}",
      uuid,
      offender.crn,
      request.requestedBy,
      request.reason,
    )
    return ResponseEntity.ok(saved.toSummaryDto())
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Update offender details",
    description = """Updates offender details. All fields need to be set to their desired value 
        (as in, no partial updates are allowed)
        
        Updating the check in schedule settings may trigger a notification if the new first check in date
        is *today*.""",
  )
  @ApiResponse(responseCode = "200", description = "Offender details updated")
  @ApiResponse(responseCode = "204", description = "No update required")
  @ApiResponse(responseCode = "400", description = "Can't complete operation due to offender status or invalid input")
  @ApiResponse(responseCode = "404", description = "Offender not found")
  @PostMapping("/{uuid}/update_details")
  fun updateDetails(
    @Parameter(description = "Offender UUID", required = true) @PathVariable uuid: UUID,
    @Valid @RequestBody request: OffenderDetailsUpdateRequest,
  ): ResponseEntity<OffenderSummaryDto> {
    val offender = offenderRepository.findByUuid(uuid).orElse(null)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Offender not found: $uuid")

    if (offender.status == OffenderStatus.INACTIVE) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Cannot update offender with status ${offender.status}. Only offenders with status INITIAL | VERIFIED can be updated.",
      )
    }

    val offenderBefore = offender.toSummaryDto()

    var updateRequired = false
    if (request.checkinSchedule != null) {
      validate(request.checkinSchedule)
      val scheduleUpdate = request.checkinSchedule
      offender.firstCheckin = scheduleUpdate.firstCheckin
      offender.checkinInterval = scheduleUpdate.checkinInterval.duration
      offender.updatedAt = clock.instant()
      updateRequired = true
    }

    if (request.contactPreference != null) {
      val preferenceUpdate = request.contactPreference
      if (offender.contactPreference != preferenceUpdate.contactPreference) {
        offender.contactPreference = preferenceUpdate.contactPreference
        offender.updatedAt = clock.instant()
        updateRequired = true
      }
    }

    if (updateRequired) {
      val saved = offenderRepository.save(offender)
      val offenderAfter = saved.toSummaryDto()
      if (newFirstCheckinDateIsToday(offenderBefore, offenderAfter, LocalDate.now(clock))) {
        LOGGER.debug("Creating check-in for offender {} as first check-in date is today", offenderAfter.uuid)
        checkinCreationService.createCheckin(offenderAfter.uuid, offenderAfter.firstCheckin, "")
      }
      return ResponseEntity.ok(offenderAfter)
    } else {
      return ResponseEntity.noContent().build()
    }
  }

  private fun validate(scheduleUpdate: CheckinScheduleUpdateRequest) {
    if (scheduleUpdate.firstCheckin.isBefore(LocalDate.now(clock))) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "First check-in date cannot be in the past")
    }
  }

  private fun getOffenderPhotoUrl(offender: OffenderV2): String? {
    if (offender.status != OffenderStatus.VERIFIED) {
      return null
    }
    val url = s3UploadService.getOffenderPhoto(offender)
    if (url == null) {
      LOGGER.info("Photo not found in S3 for offender crn={}, uuid={}", offender.crn, offender.uuid)
    }
    return url?.toString()
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
  val firstCheckin: LocalDate,
  val checkinInterval: CheckinInterval,
  val contactPreference: ContactPreference,
  val photoUrl: String? = null,
)

private fun OffenderV2.toSummaryDto(photoUrl: String? = null) = OffenderSummaryDto(
  uuid = uuid,
  crn = crn,
  status = status,
  firstCheckin = firstCheckin,
  checkinInterval = CheckinInterval.fromDuration(checkinInterval),
  contactPreference = contactPreference,
  photoUrl = photoUrl,
)

/** Request to deactivate an offender */
data class DeactivateOffenderRequest(
  @Schema(description = "Practitioner ID who requested the deactivation", required = true)
  @field:NotBlank
  val requestedBy: ExternalUserId,

  @Schema(description = "Reason for deactivation", required = true)
  @field:NotBlank
  val reason: String,
)

/** Request to reactivate an offender */
data class ReactivateOffenderRequest(
  @Schema(description = "Practitioner ID who requested the reactivation", required = true)
  @field:NotBlank
  val requestedBy: ExternalUserId,

  @Schema(description = "Reason for reactivation", required = true)
  @field:NotBlank
  val reason: String,
)

/** Request to update offender check in schedule */
data class CheckinScheduleUpdateRequest(
  @field:Schema(description = "Id of the user requesting the change", required = true)
  val requestedBy: uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId,
  @field:JsonDeserialize(using = uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocalDateDeserializer::class) val firstCheckin: LocalDate,
  val checkinInterval: CheckinInterval,
)

/** Request to update offender contact details */
data class ContactPreferenceUpdateRequest(
  @Schema(description = "Id of the user requesting the change", required = true)
  val requestedBy: ExternalUserId,
  val contactPreference: ContactPreference,
)

/**
 * Container for various offender details updates.
 *
 * Note: try grouping related details into a single data class (like schedule update)
 * and avoid adding top level fields for random bits of information. This will
 * make it clear what the semantics of the update is/should be and make validation easier.
 */
data class OffenderDetailsUpdateRequest(
  val checkinSchedule: CheckinScheduleUpdateRequest? = null,
  val contactPreference: ContactPreferenceUpdateRequest? = null,
)

private fun newFirstCheckinDateIsToday(
  beforeChange: OffenderSummaryDto,
  afterChange: OffenderSummaryDto,
  today: LocalDate,
): Boolean = beforeChange.firstCheckin != afterChange.firstCheckin && afterChange.firstCheckin == today
