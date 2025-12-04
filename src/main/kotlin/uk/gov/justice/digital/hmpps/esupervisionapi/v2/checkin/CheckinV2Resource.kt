package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import jakarta.validation.constraints.Max
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinCollectionV2Response
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinListUseCaseV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinNotificationV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Dto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CreateCheckinV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.FacialRecognitionResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.IdentityValidationResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.LogCheckinEventV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PersonalDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ReviewCheckinV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ReviewStartedRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SubmitCheckinV2Request
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.UploadLocationsV2Response
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import java.util.UUID

/**
 * V2 Checkin REST Controller
 */
@RestController
@RequestMapping("/v2/offender_checkins")
@Tag(name = "V2 Checkins", description = "V2 Checkin endpoints for offenders and practitioners")
class CheckinV2Resource(
  private val checkinService: CheckinV2Service,
) {
  @GetMapping
  @Operation(
    summary = "List checkins",
    description = "Retrieve paginated list of checkins for a practitioner with optional filtering",
  )
  @ApiResponse(responseCode = "200", description = "Checkins retrieved successfully")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun listCheckins(
    @Parameter(description = "Practitioner ID", required = true)
    @RequestParam("practitioner") practitionerId: ExternalUserId,
    @Parameter(description = "Filter by offender UUID", required = false)
    @RequestParam(name = "offenderId", required = false) offenderId: UUID?,
    @Parameter(description = "Filter by use case: NEEDS_ATTENTION, REVIEWED, AWAITING_CHECKIN", required = false)
    @RequestParam(name = "useCase", required = false) useCase: CheckinListUseCaseV2?,
    @Parameter(description = "Page number (zero-indexed)", required = false)
    @RequestParam(defaultValue = "0") page: Int,
    @Parameter(description = "Page size", required = false)
    @RequestParam(defaultValue = "20") @Max(100) size: Int,
    @Parameter(description = "Sort direction (ASC or DESC)", required = false)
    @RequestParam(defaultValue = "DESC") direction: String,
  ): ResponseEntity<CheckinCollectionV2Response> {
    val sortDirection = Sort.Direction.fromString(direction)
    val pageRequest = PageRequest.of(page, size, Sort.by(sortDirection, "dueDate"))
    val result = checkinService.listCheckins(practitionerId, offenderId, useCase, pageRequest)
    return ResponseEntity.ok(result)
  }

  @GetMapping("/{uuid}")
  @Operation(
    summary = "Get checkin by UUID",
    description = "Retrieve checkin details. Used by both offenders and practitioners.",
  )
  @ApiResponse(responseCode = "200", description = "Checkin found")
  @ApiResponse(responseCode = "404", description = "Checkin not found")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun getCheckin(
    @Parameter(description = "Checkin UUID", required = true)
    @PathVariable uuid: UUID,
    @Parameter(description = "Include personal details from Ndilius", required = false)
    @RequestParam(name = "include-personal-details", defaultValue = "false") includePersonalDetails: Boolean,
  ): ResponseEntity<CheckinV2Dto> {
    val checkin = checkinService.getCheckin(uuid, includePersonalDetails)
    return ResponseEntity.ok(checkin)
  }

  @PostMapping("/{uuid}/identity-verify")
  @Operation(
    summary = "Verify offender identity against Ndilius",
    description = "Validate personal details (name, DOB, CRN) against Ndilius before allowing checkin submission",
  )
  @ApiResponse(responseCode = "200", description = "Identity validation result")
  @ApiResponse(responseCode = "404", description = "Checkin not found")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun identityVerify(
    @Parameter(description = "Checkin UUID", required = true)
    @PathVariable uuid: UUID,
    @RequestBody @Valid personalDetails: PersonalDetails,
  ): ResponseEntity<IdentityValidationResponse> {
    val result = checkinService.validateIdentity(uuid, personalDetails)
    return ResponseEntity.ok(result)
  }

  @PostMapping("/{uuid}/upload_location")
  @Operation(
    summary = "Get upload locations for video and snapshots",
    description = "Returns presigned S3 URLs for uploading checkin media",
  )
  @ApiResponse(responseCode = "200", description = "Upload locations generated")
  @ApiResponse(responseCode = "404", description = "Checkin not found")
  @ApiResponse(responseCode = "400", description = "Invalid checkin state")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun getUploadLocations(
    @Parameter(description = "Checkin UUID", required = true)
    @PathVariable uuid: UUID,
    @Parameter(description = "Video content type", required = false)
    @RequestParam(name = "video", defaultValue = "video/mp4") videoContentType: String,
    @Parameter(description = "Snapshot content types", required = false)
    @RequestParam(name = "snapshots", required = false) snapshotContentTypes: List<String> = listOf("image/jpeg"),
  ): ResponseEntity<UploadLocationsV2Response> {
    val locations = checkinService.getUploadLocations(uuid, videoContentType, snapshotContentTypes)
    return ResponseEntity.ok(locations)
  }

  @PostMapping("/{uuid}/video-verify")
  @Operation(
    summary = "Verify face against setup photo",
    description = "Performs facial recognition using uploaded snapshot(s) against offender's setup photo. " +
            "Call this after uploading video/snapshot but before submission to show user the result. " +
            "User can re-record if NO_MATCH or proceed anyway.",
  )
  @ApiResponse(responseCode = "200", description = "Facial recognition result")
  @ApiResponse(responseCode = "404", description = "Checkin not found")
  @ApiResponse(responseCode = "400", description = "Invalid state or missing data")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun videoVerify(
    @Parameter(description = "Checkin UUID", required = true)
    @PathVariable uuid: UUID,
    @Parameter(description = "Number of snapshots to compare", required = false)
    @RequestParam(name = "numSnapshots", defaultValue = "1") numSnapshots: Int,
  ): ResponseEntity<FacialRecognitionResult> {
    val result = checkinService.verifyFace(uuid, numSnapshots)
    return ResponseEntity.ok(result)
  }

  @PostMapping("/{uuid}/submit")
  @Operation(
    summary = "Submit checkin",
    description = "Submit checkin with survey responses after uploading media",
  )
  @ApiResponse(responseCode = "200", description = "Checkin submitted successfully")
  @ApiResponse(responseCode = "404", description = "Checkin not found")
  @ApiResponse(responseCode = "400", description = "Invalid state or missing data")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun submitCheckin(
    @Parameter(description = "Checkin UUID", required = true)
    @PathVariable uuid: UUID,
    @RequestBody @Valid request: SubmitCheckinV2Request,
  ): ResponseEntity<CheckinV2Dto> {
    val checkin = checkinService.submitCheckin(uuid, request)
    return ResponseEntity.ok(checkin)
  }

  @PostMapping("/{uuid}/review-started")
  @Operation(
    summary = "Mark review as started",
    description = "Called when practitioner opens checkin in MPOP to start review",
  )
  @ApiResponse(responseCode = "200", description = "Review started")
  @ApiResponse(responseCode = "404", description = "Checkin not found")
  @ApiResponse(responseCode = "400", description = "Invalid state")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun startReview(
    @Parameter(description = "Checkin UUID", required = true)
    @PathVariable uuid: UUID,
    @RequestBody @Valid request: ReviewStartedRequest,
  ): ResponseEntity<CheckinV2Dto> {
    val checkin = checkinService.startReview(uuid, request.practitionerId)
    return ResponseEntity.ok(checkin)
  }

  @PostMapping("/{uuid}/review")
  @Operation(
    summary = "Complete checkin review",
    description = "Practitioner completes review and marks checkin as REVIEWED",
  )
  @ApiResponse(responseCode = "200", description = "Review completed")
  @ApiResponse(responseCode = "404", description = "Checkin not found")
  @ApiResponse(responseCode = "400", description = "Invalid state")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun reviewCheckin(
    @Parameter(description = "Checkin UUID", required = true)
    @PathVariable uuid: UUID,
    @RequestBody @Valid request: ReviewCheckinV2Request,
  ): ResponseEntity<CheckinV2Dto> {
    val checkin = checkinService.reviewCheckin(uuid, request)
    return ResponseEntity.ok(checkin)
  }

  @GetMapping("/{uuid}/proxy/video")
  @Operation(
    summary = "Get video proxy URL",
    description = "Returns presigned S3 URL for viewing checkin video (for MPOP)",
  )
  @ApiResponse(responseCode = "200", description = "Video URL")
  @ApiResponse(responseCode = "404", description = "Video not found")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun getVideoProxyUrl(
    @Parameter(description = "Checkin UUID", required = true)
    @PathVariable uuid: UUID,
  ): ResponseEntity<Map<String, String>> {
    val url = checkinService.getVideoProxyUrl(uuid)
    return ResponseEntity.ok(mapOf("url" to url.toString()))
  }

  @GetMapping("/{uuid}/proxy/snapshot")
  @Operation(
    summary = "Get snapshot proxy URL",
    description = "Returns presigned S3 URL for viewing checkin snapshot (for MPOP)",
  )
  @ApiResponse(responseCode = "200", description = "Snapshot URL")
  @ApiResponse(responseCode = "404", description = "Snapshot not found")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun getSnapshotProxyUrl(
    @Parameter(description = "Checkin UUID", required = true)
    @PathVariable uuid: UUID,
    @Parameter(description = "Snapshot index", required = false)
    @RequestParam(name = "index", defaultValue = "0") index: Int,
  ): ResponseEntity<Map<String, String>> {
    val url = checkinService.getSnapshotProxyUrl(uuid, index)
    return ResponseEntity.ok(mapOf("url" to url.toString()))
  }

  @PostMapping
  @Operation(
    summary = "DEBUG: Manual checkin creation",
    description = "DEBUG ONLY - Manually create a checkin outside the automated job schedule. Use for testing purposes.",
  )
  @ApiResponse(responseCode = "200", description = "Checkin created")
  @ApiResponse(responseCode = "404", description = "Offender not found")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun createCheckin(
    @RequestBody @Valid request: CreateCheckinV2Request,
  ): ResponseEntity<CheckinV2Dto> {
    val checkin = checkinService.createCheckin(request)
    return ResponseEntity.ok(checkin)
  }

  @PostMapping("/{uuid}/invite")
  @Operation(
    summary = "DEBUG: Manual notification trigger",
    description = "DEBUG ONLY - Manually trigger notifications for a checkin outside the automated event flow. Use for testing purposes.",
  )
  @ApiResponse(responseCode = "200", description = "Notification sent")
  @ApiResponse(responseCode = "404", description = "Checkin not found")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun sendInvite(
    @Parameter(description = "Checkin UUID", required = true)
    @PathVariable uuid: UUID,
    @RequestBody @Valid request: CheckinNotificationV2Request,
  ): ResponseEntity<CheckinV2Dto> {
    val checkin = checkinService.sendInvite(uuid, request)
    return ResponseEntity.ok(checkin)
  }

  @PostMapping("/{uuid}/log-event")
  @Operation(
    summary = "Log checkin audit event",
    description = "Record security or audit events (e.g., outside access attempts, geolocation issues)",
  )
  @ApiResponse(responseCode = "200", description = "Event logged successfully")
  @ApiResponse(responseCode = "404", description = "Checkin not found")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun logEvent(
    @Parameter(description = "Checkin UUID", required = true)
    @PathVariable uuid: UUID,
    @RequestBody @Valid request: LogCheckinEventV2Request,
  ): ResponseEntity<Map<String, String>> {
    val eventUuid = checkinService.logCheckinEvent(uuid, request)
    return ResponseEntity.ok(mapOf("eventUuid" to eventUuid.toString()))
  }
}
