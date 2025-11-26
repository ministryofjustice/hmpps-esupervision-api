package uk.gov.justice.digital.hmpps.esupervisionapi.v1.offender

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
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
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinListUseCase
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinResource
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinSubmission
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinEventRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinNotificationRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinReviewRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinUploadLocationResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CreateCheckinRequest
import java.util.UUID

@RestController
@RequestMapping(path = ["/v1/offender_checkins"])
@Validated
class OffenderCheckinResourceV1(
  val offenderCheckinResource: OffenderCheckinResource,
) {

//  val uploadTTl: Duration = Duration.ofMinutes(uploadTTlMinutes)

  @GetMapping(produces = [APPLICATION_JSON_VALUE])
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun getCheckins(
    @RequestParam("practitioner") practitionerId: ExternalUserId,
    @Parameter(description = "Filter by a offender UUID")
    @RequestParam(name = "offenderId", required = false) offenderId: UUID?,
    @Parameter(description = "Hint for which UI tab/use-case to fetch: NEEDS_ATTENTION, REVIEWED, AWAITING_CHECKIN. If omitted, returns all.")
    @RequestParam(name = "useCase", required = false) useCase: CheckinListUseCase?,
    @Parameter(description = "Zero-based page index")
    @RequestParam(defaultValue = "0") page: Int,
    @RequestParam(defaultValue = "20") @Max(100) size: Int,
    @Parameter(description = "Sort by due date (sort direction ASC or DESC)")
    @RequestParam(defaultValue = "DESC") direction: String,
  ): ResponseEntity<CollectionDto<OffenderCheckinDto>> {
    return offenderCheckinResource.getCheckins(practitionerId, offenderId, useCase, page, size, direction)
  }

  @GetMapping("/{uuid}")
  @Tag(name = "offender")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun getCheckin(
    @PathVariable uuid: UUID,
    @RequestParam(name = "include-uploads", required = false, defaultValue = "false") includeUploads: Boolean,
  ): ResponseEntity<OffenderCheckinResponse> {
    return offenderCheckinResource.getCheckin(uuid, includeUploads)
  }

  @PostMapping
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun createCheckin(@RequestBody @Valid createCheckin: CreateCheckinRequest, bindingResult: BindingResult): ResponseEntity<OffenderCheckinDto> {
    return offenderCheckinResource.createCheckin(createCheckin, bindingResult)
  }

  @PostMapping("/{uuid}/upload_location")
  @Tag(name = "offender")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun uploadLocation(
    @PathVariable uuid: UUID,
    @RequestParam(name = "snapshots", required = false) snapshotContentTypes: List<String> = listOf(),
    @RequestParam(name = "video", required = true) videoContentType: String,
    @RequestParam("reference", required = true) referenceContentType: String,
  ): ResponseEntity<CheckinUploadLocationResponse> {
    return offenderCheckinResource.uploadLocation(uuid, snapshotContentTypes, videoContentType, referenceContentType)
  }

  @PostMapping("/{uuid}/submit")
  @Tag(name = "offender")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun submitCheckin(@PathVariable uuid: UUID, @RequestBody @Valid checkinInput: OffenderCheckinSubmission): ResponseEntity<OffenderCheckinDto> {
    return offenderCheckinResource.submitCheckin(uuid, checkinInput)
  }

  @PostMapping("/{uuid}/review")
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun reviewCheckin(
    @PathVariable uuid: UUID,
    @RequestBody @Valid reviewRequest: CheckinReviewRequest,
    bindingResult: BindingResult,
  ): ResponseEntity<OffenderCheckinDto> {
    return offenderCheckinResource.reviewCheckin(uuid, reviewRequest, bindingResult)
  }

  @PostMapping("/{uuid}/auto_id_verify")
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun autoVerifyCheckin(
    @PathVariable uuid: UUID,
    @RequestParam numSnapshots: Int,
  ): ResponseEntity<AutomatedVerificationResult> {
    return offenderCheckinResource.autoVerifyCheckin(uuid, numSnapshots)
  }

  @PostMapping("/{uuid}/invite")
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Send checkin invite notification to the offender",
    description = "Meant to be used for one-off notifications. Potentially updates the due date of a checkin to 'today'",
  )
  fun notify(
    @PathVariable uuid: UUID,
    @RequestBody @Valid notificationRequest: CheckinNotificationRequest,
    bindingResult: BindingResult,
  ): ResponseEntity<OffenderCheckinDto> {
    return offenderCheckinResource.notify(uuid, notificationRequest, bindingResult)
  }

  @PostMapping("/{uuid}/event")
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Post a checkin related event",
    description = "An 'event' might be something used for metrics or audit.",
  )
  fun event(
    @PathVariable uuid: UUID,
    @RequestBody @Valid checkinEvent: CheckinEventRequest,
    bindingResult: BindingResult,
  ): ResponseEntity<Map<String, String>> {
    return offenderCheckinResource.event(uuid, checkinEvent, bindingResult)
  }
}
