package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CreateCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.UploadLocationResponse
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping(path = ["/offender_checkins"])
class OffenderCheckinResource(
  val offenderCheckinService: OffenderCheckinService,
) {

  @GetMapping(produces = [APPLICATION_JSON_VALUE])
  @Tag(name = "practitioner")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun getCheckins(): ResponseEntity<CollectionDto<OffenderCheckinDto>> {
    val pageRequest = PageRequest.of(0, 20)
    val checkins = offenderCheckinService.getCheckins(pageRequest)
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
  fun createCheckin(@RequestBody @Valid createCheckin: CreateCheckinRequest): ResponseEntity<OffenderCheckinDto> {
    val checkin = offenderCheckinService.createCheckin(createCheckin)
    return ResponseEntity.ok(checkin)
  }

  @PostMapping("/{uuid}/upload_location")
  @Tag(name = "offender")
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  fun uploadLocation(
    @PathVariable uuid: UUID,
    @RequestParam(name = "content-type", required = true) contentType: String,
  ): ResponseEntity<UploadLocationResponse> {
    val duration = Duration.ofMinutes(10) // TODO: get that from config
    val url = offenderCheckinService.generateVideoUploadLocation(uuid, contentType, duration)
    return ResponseEntity.ok(
      UploadLocationResponse(
        locationInfo = LocationInfo(url, contentType, duration.toString()),
      ),
    )
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
  fun reviewCheckin(@PathVariable uuid: UUID): ResponseEntity<OffenderCheckinDto> {
    TODO("not implemented yet")
  }
}
