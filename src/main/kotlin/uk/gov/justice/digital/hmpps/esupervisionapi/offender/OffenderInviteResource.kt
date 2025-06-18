package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import software.amazon.awssdk.services.s3.model.S3Exception
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.InviteInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.OffenderInviteConfirmation
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.OffenderInviteDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.Pagination
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import java.time.Duration
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
class OffenderInviteResource(
  private val offenderInviteService: OffenderInviteService,
  private val s3UploadService: S3UploadService,
) {

  @PreAuthorize("hasAnyRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "offender")
  @GetMapping("/{uuid}", produces = ["application/json"])
  fun getInvite(@PathVariable uuid: UUID): ResponseEntity<OffenderInviteDto> {
    val invite = offenderInviteService.findByUUID(uuid)
    if (invite.isPresent) {
      return ResponseEntity.ok(invite.get().dto())
    }

    return ResponseEntity.notFound().build()
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(
    summary = "Returns a collection of invite records",
    description = "The returned invites can be linked to any practitioner.",
  )
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
  @Tag(name = "practitioner")
  @Operation(
    summary = "Creates and starts the invite process.",
    description = """For each successfully created invite a notification will be scheduled (via specified contact method). 
      When invite could not be created, `errorMessage` will be set.""",
  )
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
  @Tag(name = "offender")
  @Operation(
    summary = "Confirm the invite on behalf the offender",
    description = """This step requires that a photo was uploaded (see `/offender_invites/upload_location`)
      and the submitted information matches the information in the invite record. 
      Once confirmed, the practitioner will need to approve the submission (e.g. the photo) 
      via `/offender_invites/approve`.
    """,
  )
  @PostMapping("/{uuid}/confirm")
  fun confirmInvite(
    @PathVariable uuid: UUID,
    @ModelAttribute confirmationInfo: OffenderInviteConfirmation,
  ): ResponseEntity<ConfirmationResultDto> {
    val invite = offenderInviteService.findByUUID(uuid)
    if (invite.isEmpty) {
      return ResponseEntity.badRequest().body(ConfirmationResultDto(error = "Invite not found"))
    }
    if (!s3UploadService.isInvitePhotoUploaded(invite.get())) {
      return ResponseEntity.badRequest().body(ConfirmationResultDto(error = "No photo uploaded"))
    }

    try {
      val updatedInvite = offenderInviteService.confirmOffenderInvite(uuid, confirmationInfo)
      if (updatedInvite.isEmpty) {
        // the invite status changed already, we did not perform an update
        return ResponseEntity.badRequest().body(ConfirmationResultDto(error = "Could not confirm offender invite (invalid status change)"))
      }

      return ResponseEntity.ok(ConfirmationResultDto())
    } catch (e: S3Exception) {
      LOG.warn("confirmInvite(invite={}), S3 failure: {}", uuid, e.message)
      throw e
    }
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "practitioner")
  @Operation(
    summary = "Approve the invite and add offender to the system",
    description = """To be called on behalf the practitioner when data supplied by the offender confirms their identity.
    Once approved, the practitioner will be able to schedule "checkins." """,
  )
  @PostMapping("/{uuid}/approve")
  fun approveInvite(@PathVariable uuid: UUID): ResponseEntity<Map<String, String>> {
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

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Tag(name = "offender")
  @Operation(
    summary = "Request a photo upload location (an URL)",
    description = """The returned URL expires after 5 minutes.
      To upload the image, the client must use `PUT` method and use the same content-type 
      as the one passed to this endpoint.""",
  )
  @PostMapping("/{uuid}/upload_location")
  fun invitePhotoUploadLocation(
    @PathVariable uuid: UUID,
    @RequestParam(name = "content-type", required = true) contentType: String,
  ): ResponseEntity<Map<String, String>> {
    val supportedContentTypes = setOf("image/jpeg", "image/jpg", "image/png")
    if (!supportedContentTypes.contains(contentType)) {
      return ResponseEntity.badRequest().body(mapOf("error" to "supported content types: $supportedContentTypes"))
    }
    val invite = offenderInviteService.findByUUID(uuid)
    if (invite.isEmpty) {
      return ResponseEntity.badRequest().body(
        mapOf(
          "error" to "No invite found with id $uuid",
        ),
      )
    }

    val duration = Duration.ofMinutes(5)
    val url = s3UploadService.generatePresignedUploadUrl(invite.get(), contentType, duration)
    LOG.debug("generated invite photo upload url: {}", url)
    return ResponseEntity.ok(
      mapOf(
        "url" to "$url",
        "contentType" to contentType,
        "duration" to duration.toString(),
      ),
    )
  }

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(this::class.java)
  }
}
