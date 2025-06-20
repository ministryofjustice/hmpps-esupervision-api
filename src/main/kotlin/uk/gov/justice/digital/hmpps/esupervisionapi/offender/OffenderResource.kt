package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.toPagination

@RestController
@RequestMapping("/offenders", produces = ["application/json"])
class OffenderResource(
  private val offenderService: OffenderService,
) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(summary = "Returns a collection of offender records")
  @GetMapping
  fun getOffenders(): ResponseEntity<CollectionDto<OffenderDto>> {
    val pageRequest = PageRequest.of(0, 20)
    val offenders = offenderService.getOffenders(pageable = pageRequest)
    return ResponseEntity.ok(
      CollectionDto(
        pagination = pageRequest.toPagination(),
        content = offenders.content,
      ),
    )
  }
}
