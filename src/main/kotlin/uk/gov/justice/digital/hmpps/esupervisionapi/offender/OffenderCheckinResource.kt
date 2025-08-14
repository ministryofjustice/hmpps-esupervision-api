package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
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
import software.amazon.awssdk.services.s3.S3Client
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinReviewRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinUploadLocationResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CreateCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.intoResponseStatusException
import java.time.Clock
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping(path = ["/offender_checkins"])
@Validated
class OffenderCheckinResource(
  val clock: Clock,
  val offenderCheckinService: OffenderCheckinService,
  @Qualifier("rekognitionS3Client") val rekognitionS3: S3Client,
  @Value("\${rekognition.s3_bucket_name}") val rekogBucketName: String,
  @Value("\${app.upload-ttl-minutes}") val uploadTTlMinutes: Long,
) {

  val uploadTTl: Duration = Duration.ofMinutes(uploadTTlMinutes)

  @GetMapping(produces = [APPLICATION_JSON_VALUE])
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun getCheckins(
    @RequestParam("practitionerUuid") practitionerUuid: String,
    @Parameter(description = "Zero-based page index")
    @RequestParam(defaultValue = "0") page: Int,
    @RequestParam(defaultValue = "20") @Max(100) size: Int,
  ): ResponseEntity<CollectionDto<OffenderCheckinDto>> {
    val pageRequest = PageRequest.of(page, size)
    val checkins = offenderCheckinService.getCheckins(practitionerUuid, pageRequest)
    return ResponseEntity.ok(checkins)
  }

  @GetMapping("/{uuid}")
  @Tag(name = "offender")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun getCheckin(
    @PathVariable uuid: UUID,
    @RequestParam(name = "include-uploads", required = false, defaultValue = "false") includeUploads: Boolean,
  ): ResponseEntity<OffenderCheckinDto> = ResponseEntity.ok(offenderCheckinService.getCheckin(uuid, includeUploads))

  @PostMapping
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun createCheckin(@RequestBody @Valid createCheckin: CreateCheckinRequest, bindingResult: BindingResult): ResponseEntity<OffenderCheckinDto> {
    if (bindingResult.hasErrors()) {
      throw intoResponseStatusException(bindingResult)
    }
    val created = offenderCheckinService.createCheckin(createCheckin, SingleNotificationContext.forCheckin(clock))
    return ResponseEntity.ok(created.checkin)
  }

  @PostMapping("/{uuid}/upload_location")
  @Tag(name = "offender")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun uploadLocation(
    @PathVariable uuid: UUID,
    @RequestParam(name = "snapshots", required = false) snapshotContentTypes: List<String> = listOf(),
    @RequestParam(name = "video", required = true) videoContentType: String,
    @RequestParam("reference", required = true) referenceContentType: String,
  ): ResponseEntity<CheckinUploadLocationResponse> = ResponseEntity.ok(
    offenderCheckinService.generateUploadLocations(
      uuid,
      UploadLocationTypes(
        reference = referenceContentType,
        video = videoContentType,
        snapshots = snapshotContentTypes,
      ),
      uploadTTl,
    ),
  )

  @PostMapping("/{uuid}/submit")
  @Tag(name = "offender")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun submitCheckin(@PathVariable uuid: UUID, @RequestBody @Valid checkinInput: OffenderCheckinSubmission): ResponseEntity<OffenderCheckinDto> {
    val checkin = offenderCheckinService.submitCheckin(uuid, checkinInput)
    return ResponseEntity.ok(checkin)
  }

  @PostMapping("/{uuid}/review")
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun reviewCheckin(
    @PathVariable uuid: UUID,
    @RequestBody @Valid reviewRequest: CheckinReviewRequest,
    bindingResult: BindingResult,
  ): ResponseEntity<OffenderCheckinDto> {
    if (bindingResult.hasErrors()) {
      throw intoResponseStatusException(bindingResult)
    }
    val checkin = offenderCheckinService.reviewCheckin(uuid, reviewRequest)
    return ResponseEntity.ok(checkin)
  }

  @PostMapping("/{uuid}/auto_id_verify")
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun autoVerifyCheckin(
    @PathVariable uuid: UUID,
    @RequestParam numSnapshots: Int,
  ): ResponseEntity<AutomatedVerificationResult> {
    val passed = offenderCheckinService.verifyCheckinIdentity(uuid, numSnapshots)
    val dto = AutomatedVerificationResult(passed)
    return ResponseEntity.ok(dto)
  }
}
