package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
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
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.services.s3.S3Client
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinReviewRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CreateCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.UploadLocationResponse
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping(path = ["/offender_checkins"])
@Validated
class OffenderCheckinResource(
  val offenderCheckinService: OffenderCheckinService,
  @Qualifier("rekognitionS3Client") val rekognitionS3: S3Client,
  @Value("\${rekognition.s3_bucket_name}") val rekogBucketName: String,
) {

  @GetMapping(produces = [APPLICATION_JSON_VALUE])
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun getCheckins(@RequestParam("practitionerUuid") practitionerUuid: String): ResponseEntity<CollectionDto<OffenderCheckinDto>> {
    val pageRequest = PageRequest.of(0, 20)
    val checkins = offenderCheckinService.getCheckins(practitionerUuid, pageRequest)
    return ResponseEntity.ok(checkins)
  }

  @GetMapping("/{uuid}")
  @Tag(name = "offender")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun getCheckin(@PathVariable uuid: UUID): ResponseEntity<OffenderCheckinDto> {
    val checkin = offenderCheckinService.getCheckin(uuid)
    if (checkin.isPresent) {
      return ResponseEntity.ok(checkin.get())
    }

    return ResponseEntity.notFound().build()
  }

  @PostMapping
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun createCheckin(@RequestBody @Valid createCheckin: CreateCheckinRequest, bindingResult: BindingResult): ResponseEntity<OffenderCheckinDto> {
    if (bindingResult.hasErrors()) {
      throw intoResponseStatusException(bindingResult)
    }
    val checkin = offenderCheckinService.createCheckin(createCheckin)
    return ResponseEntity.ok(checkin)
  }

  @PostMapping("/{uuid}/upload_location")
  @Tag(name = "offender")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun uploadLocation(
    @PathVariable uuid: UUID,
    @RequestParam(name = "content-type", required = true) @NotBlank contentType: String,
    @RequestParam(name = "num-snapshots", required = false) @Min(1) numSnapshots: Int? = null,
  ): ResponseEntity<UploadLocationResponse> {
    val duration = Duration.ofMinutes(10) // TODO: get that from config
    var response: UploadLocationResponse? = null
    if (numSnapshots != null) {
      val urls = offenderCheckinService.generatePhotoSnapshotLocations(uuid, contentType, numSnapshots, duration)
      response = UploadLocationResponse(locationInfo = null, locations = urls.map { LocationInfo(it, contentType, duration.toString()) })
    } else {
      val url = offenderCheckinService.generateVideoUploadLocation(uuid, contentType, duration)
      response = UploadLocationResponse(LocationInfo(url, contentType, duration.toString()))
    }
    return ResponseEntity.ok(response)
  }

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

  @PostMapping("/{uuid}/auto_id_check")
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun automatedIdentityCheck(@PathVariable uuid: UUID, @RequestParam result: AutomatedIdVerificationResult): ResponseEntity<OffenderCheckinDto> {
    val checkin = offenderCheckinService.setAutomatedIdCheckStatus(uuid, result)
    return ResponseEntity.ok(checkin)
  }

  private fun intoResponseStatusException(bindingResult: BindingResult): ResponseStatusException {
    val errors = bindingResult.fieldErrors.associateBy({ it.field }, { it.defaultMessage })
    return ResponseStatusException(HttpStatus.BAD_REQUEST, errors.toString())
  }
}
