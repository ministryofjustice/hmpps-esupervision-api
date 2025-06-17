package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import jakarta.validation.Valid
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.services.s3.model.S3Exception
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.InviteInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.OffenderInviteConfirmation
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.OffenderInviteDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.Pagination
import java.util.UUID

data class ConfirmationResultDto(
  // val offenderUuid: UUID? = null,
  val error: String? = null,
)

data class OffenderInvitesDto(
  val pagination: Pagination,
  val content: List<OffenderInviteDto>,
)

@RestController
@RequestMapping("/offender_invites", produces = ["application/json"])
class OffenderInviteResource(private val offenderInviteService: OffenderInviteService) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping()
  fun getInvites(pageable: Pageable): ResponseEntity<OffenderInvitesDto> {
    //  val authentication = SecurityContextHolder.getContext().authentication
    val page = offenderInviteService.getAllOffenderInvites(pageable)
    val result = OffenderInvitesDto(
      pagination = Pagination(pageNumber = 0, pageSize = 20),
      content = page,
    )
    return ResponseEntity.ok(result)
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @PostMapping("/")
  fun createInvites(@RequestBody @Valid inviteInfo: InviteInfo): ResponseEntity<AggregateCreateInviteResult> {
    LOG.info("Creating offender invites")
    val result = offenderInviteService.createOffenderInvites(inviteInfo)
    if (result.importedRecords == 0) {
      return ResponseEntity.badRequest().body(result)
    }
    return ResponseEntity.ok(result)
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @PostMapping("/confirm")
  fun confirmInvite(
    @ModelAttribute confirmationInfo: OffenderInviteConfirmation,
    @RequestParam("image") image: MultipartFile,
  ): ResponseEntity<ConfirmationResultDto> {
    if (image.isEmpty) {
      return ResponseEntity.badRequest().body(ConfirmationResultDto(error = "Image cannot be empty"))
    }
    val invite = offenderInviteService.findByUUID(confirmationInfo.inviteUuid)
    if (invite.isEmpty) {
      return ResponseEntity.badRequest().body(ConfirmationResultDto(error = "Invite not found"))
    }

    try {
      val updatedInvite = offenderInviteService.confirmOffenderInvite(confirmationInfo, image)
      if (updatedInvite.isEmpty) {
        // the invite status changed already, we did not perform an update
        return ResponseEntity.badRequest().body(ConfirmationResultDto(error = "Could not confirm offender invite"))
      }

      return ResponseEntity.ok(ConfirmationResultDto())
    } catch (e: S3Exception) {
      LOG.warn("confirmInvite(invite={}), S3 failure: {}", confirmationInfo.inviteUuid, e.message)
      throw e
    }
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @PostMapping("/approve")
  fun approveInvite(@RequestParam uuid: UUID): ResponseEntity<Map<String, String>> {
    // TODO: settle on return value
    val invite = offenderInviteService.findByUUID(uuid)
    if (invite.isEmpty) {
      return ResponseEntity.badRequest().body(
        mapOf(
          "error" to "No invite found with id $uuid",
        ),
      )
    }

    val offender = offenderInviteService.approveOffenderInvite(invite.get())
    return ResponseEntity.ok(
      mapOf(
        "message" to "invite approved",
        "offender" to offender.uuid.toString(),
      ),
    )
  }

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(this::class.java)
  }
}
