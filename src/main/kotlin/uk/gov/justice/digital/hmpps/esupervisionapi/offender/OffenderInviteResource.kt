package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import jakarta.validation.Valid
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.InviteInfo

@RestController
@RequestMapping("/offender_invites", produces = ["application/json"])
class OffenderInviteResource(private val offenderInviteService: OffenderInviteService) {

  @PreAuthorize("hasRole('ESUP_PRACTITIONER')")
  @GetMapping("/")
  fun getInvites(pageable: Pageable): ResponseEntity<Page<OffenderInvite>> {
    val page = offenderInviteService.getAllOffenderInvites(pageable)
    return ResponseEntity.ok(page)
  }

  @PreAuthorize("hasRole('ESUP_PRACTITIONER')")
  @PostMapping("/")
  fun createInvites(@RequestBody @Valid inviteInfo: InviteInfo): ResponseEntity<AggregateCreateInviteResult> {
    LOG.info("Creating offender invites")
    val result = offenderInviteService.createOffenderInvites(inviteInfo)
    if (result.importedRecords == 0) {
      return ResponseEntity.badRequest().body(result)
    }
    return ResponseEntity.ok(result)
  }

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(this::class.java)
  }
}
