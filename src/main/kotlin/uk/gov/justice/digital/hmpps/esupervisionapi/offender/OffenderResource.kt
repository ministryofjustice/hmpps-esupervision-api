package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.Pagination
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.toPagination

data class Offenders(
  val pagination: Pagination,
  val content: List<OffenderDto>,
)

@RestController
@RequestMapping("/offenders", produces = ["application/json"])
class OffenderResource(
  private val offenderService: OffenderService,
  private val offenderRepository: OffenderRepository,
) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping
  fun getOffenders(): ResponseEntity<Offenders> {
    val pageRequest = PageRequest.of(0, 20)
    val offenders = offenderService.getOffenders(pageable = pageRequest)
    return ResponseEntity.ok(
      Offenders(
        pagination = pageRequest.toPagination(),
        content = offenders.content,
      ),
    )
  }
}
