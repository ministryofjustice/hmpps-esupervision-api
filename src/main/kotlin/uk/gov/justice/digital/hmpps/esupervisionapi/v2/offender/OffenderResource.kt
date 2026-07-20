package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INamedPerson
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.checkinIneligibilityReason
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto.UploadHashRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto.UploadLocationResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.resolveUploadHash
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup.OffenderSetupService
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/v2/offenders", produces = ["application/json"])
@Tag(name = "V2 Offenders", description = "V2 offender endpoints")
class OffenderResource(
  private val offenderRepository: OffenderRepository,
  private val s3UploadService: S3UploadService,
  private val clock: Clock,
  private val checkinCreationService: CheckinCreationService,
  private val eventAuditService: EventAuditService,
  private val ndiliusApiClient: INdiliusApiClient,
  private val notificationService: NotificationService,
  private val checkinRepository: OffenderCheckinRepository,
  private val offenderSetupService: OffenderSetupService,
  private val offenderDeactivationService: OffenderDeactivationService,
  private val offenderService: OffenderService,
  private val appConfig: AppConfig,
) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Get offender by CRN",
    description = "Returns offender registration details. Does not include PII by default.",
  )
  @ApiResponse(responseCode = "200", description = "Offender found")
  @ApiResponse(responseCode = "404", description = "Offender not found")
  @GetMapping("/crn/{crn}")
  fun getOffenderByCrn(
    @Parameter(description = "Case Reference Number", required = true) @PathVariable crn: String,
    @Parameter(description = "Include PII from NDdelius", required = false)
    @RequestParam(name = "include-personal-details", required = false, defaultValue = "false") includePersonalDetails: Boolean,
  ): ResponseEntity<OffenderSummaryDto> {
    val normalisedCrn = crn.trim().uppercase()
    val offender = offenderRepository.findByCrn(normalisedCrn).orElse(null)
    if (offender == null) {
      LOGGER.info("Offender not found for crn={}", crn)
      return ResponseEntity.notFound().build()
    }
    val contactDetails = if (includePersonalDetails) ndiliusApiClient.getContactDetails(normalisedCrn) else null

    val detailsMesage = if (!includePersonalDetails) {
      "skipped"
    } else if (contactDetails != null) {
      "fetched"
    } else {
      "not found"
    }
    LOGGER.info("Found offender by CRN: crn={}, status={}, contactDetails={}", normalisedCrn, offender.status, detailsMesage)
    return ResponseEntity.ok(offender.toSummaryDto(getOffenderPhotoUrl(offender), contactDetails))
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Get offender header by CRN",
    description = "Returns offender header details. Returns 404 if not found.",
  )
  @ApiResponse(responseCode = "200", description = "Offender found")
  @ApiResponse(responseCode = "404", description = "Offender not found")
  @GetMapping("/header/{crn}")
  fun getOffenderHeaderByCrn(
    @Parameter(description = "Case Reference Number", required = true) @PathVariable crn: String,
  ): ResponseEntity<OffenderHeaderDetails> {
    val offender = offenderRepository.findByCrn(crn.trim().uppercase()).orElse(null)
    if (offender == null) {
      LOGGER.info("Offender not found for crn={}", crn)
      return ResponseEntity.notFound().build()
    }

    val headerDetails = offenderService.getHeaderDetails(crn.trim().uppercase())

    LOGGER.info("Retrieved header details for offender by CRN: crn={}, status={}", offender.crn, offender.status)
    return ResponseEntity.ok(headerDetails)
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/{uuid}/proxy/photo")
  @Operation(
    summary = "Get photo proxy URL",
    description = "Returns presigned S3 URL for viewing offender's setup photo",
  )
  @ApiResponse(responseCode = "200", description = "Photo URL")
  @ApiResponse(responseCode = "404", description = "Offender or photo not found")
  fun getPhotoProxyUrl(
    @Parameter(description = "Offender UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<Map<String, String>> {
    val offender = offenderRepository.findByUuid(uuid).orElse(null)
    if (offender == null) {
      LOGGER.info("Photo proxy request failed: offender not found for uuid={}", uuid)
      return ResponseEntity.notFound().build()
    }

    val photoUrl = getOffenderPhotoUrl(offender)
    if (photoUrl == null) {
      LOGGER.info("Photo proxy request failed: offender uuid={} status={}", uuid, offender.status)
      return ResponseEntity.notFound().build()
    }

    LOGGER.debug("Returning photo proxy URL for offender uuid={}", uuid)
    return ResponseEntity.ok(mapOf("url" to photoUrl))
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Get photo upload location for offender",
    description = """Request a presigned S3 URL for uploading/updating the offender's reference photo.
      The returned URL expires after 5 minutes.
      Only VERIFIED offenders can have their photo updated.
      To upload the image, client must use PUT method with the specified content-type.""",
  )
  @ApiResponse(responseCode = "200", description = "Upload URL generated")
  @ApiResponse(responseCode = "400", description = "Invalid content type or offender not VERIFIED")
  @ApiResponse(responseCode = "404", description = "Offender not found")
  @PostMapping("/{uuid}/upload_location")
  fun getPhotoUploadLocation(
    @Parameter(description = "Offender UUID", required = true) @PathVariable uuid: UUID,
    @Parameter(description = "Content type of the image", required = true)
    @RequestParam(name = "content-type") contentType: String,
    @RequestBody(required = false) hashRequest: UploadHashRequest?,
  ): ResponseEntity<UploadLocationResponse> {
    val supportedContentTypes = setOf("image/jpeg", "image/jpg", "image/png")

    if (!supportedContentTypes.contains(contentType)) {
      return ResponseEntity.badRequest().body(
        UploadLocationResponse(
          locationInfo = null,
          errorMessage = "Supported content types: $supportedContentTypes",
        ),
      )
    }

    val offender = offenderRepository.findByUuid(uuid).orElse(null)
    if (offender == null) {
      LOGGER.warn("Upload location request failed: offender not found for uuid={}", uuid)
      return ResponseEntity.notFound().build()
    }

    if (offender.status != OffenderStatus.VERIFIED) {
      return ResponseEntity.badRequest().body(
        UploadLocationResponse(
          locationInfo = null,
          errorMessage = "Cannot update photo for offender with status ${offender.status}. Only VERIFIED offenders can have their photo updated.",
        ),
      )
    }

    val hash = resolveUploadHash(
      sha256Base64 = hashRequest?.sha256,
      slot = "offender-photo",
    )

    val duration = Duration.ofMinutes(5)
    val presigned = s3UploadService.generatePresignedUpload(offender, contentType, duration, hash)

    LOGGER.info("Generated photo upload URL for offender uuid={}, crn={}", uuid, offender.crn)

    return ResponseEntity.ok(
      UploadLocationResponse(
        locationInfo = LocationInfo(
          url = presigned.url,
          contentType = contentType,
          duration = duration.toString(),
          requiredHeaders = presigned.requiredHeaders.takeIf { it.isNotEmpty() },
        ),
      ),
    )
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Deactivate offender",
    description = """Deactivates a VERIFIED offender, changing their status to INACTIVE.
      This prevents further check-ins from being created or submitted.
      Only VERIFIED offenders can be deactivated.""",
  )
  @ApiResponse(responseCode = "200", description = "Offender deactivated")
  @ApiResponse(responseCode = "400", description = "Offender not in VERIFIED status")
  @ApiResponse(responseCode = "404", description = "Offender not found")
  @PostMapping("/{uuid}/deactivate")
  fun deactivateOffender(
    @Parameter(description = "Offender UUID", required = true) @PathVariable uuid: UUID,
    @Valid @RequestBody request: DeactivateOffenderRequest,
  ): ResponseEntity<OffenderSummaryDto> {
    val offender = offenderRepository.findByUuid(uuid).orElse(null)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Offender not found: $uuid")

    if (offender.status != OffenderStatus.VERIFIED) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Cannot deactivate offender with status ${offender.status}. Only VERIFIED offenders can be deactivated.",
      )
    }

    val contactDetails = try {
      ndiliusApiClient.getContactDetails(offender.crn)
        ?: throw Exception("NDelius returned null contact details")
    } catch (e: Exception) {
      LOGGER.error("Failed to fetch contact details from NDelius for CRN: ${offender.crn}", e)
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Could not verify contact details in NDelius for ${offender.crn}.",
      )
    }

    val saved = offenderDeactivationService.deactivateOffender(
      offender,
      reason = request.reason,
      contactDetails = contactDetails,
      sensitive = request.sensitive,
    )

    LOGGER.info(
      "Deactivated offender: uuid={}, crn={}, requestedBy={}, reason={}, sensitive={}",
      uuid,
      offender.crn,
      request.requestedBy,
      request.reason,
      request.sensitive,
    )

    return ResponseEntity.ok(saved.toSummaryDto(getOffenderPhotoUrl(saved)))
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Reactivate offender",
    description = """Reactivates an INACTIVE offender, changing their status back to VERIFIED.
      This allows check ins to be created and submitted again.
      Only INACTIVE offenders can be reactivated.
      This endpoint performs the following actions:
      1. Updates the offender's status to VERIFIED.
      2. Optionally updates the check in schedule (frequency and start date) and contact preferences.
      3. Sends a registration notification to the offender
      4. Automatically creates a check in record for the 'firstCheckin' date. 
         - If a check in  for that date already exists, it will skip creation to prevent duplicates.
         - If the date is in the future, the record is created in a 'CREATED' state for the background job to process later.
      Note: V1 does not support reactivation, but V2 requires it due to unique CRN constraint.""",
  )
  @ApiResponse(responseCode = "200", description = "Offender reactivated")
  @ApiResponse(responseCode = "400", description = "Offender not in INACTIVE status")
  @ApiResponse(responseCode = "404", description = "Offender not found")
  @PostMapping("/{uuid}/reactivate")
  fun reactivateOffender(
    @Parameter(description = "Offender UUID", required = true) @PathVariable uuid: UUID,
    @Valid @RequestBody request: ReactivateOffenderRequest,
  ): ResponseEntity<OffenderSummaryDto> {
    val offender = offenderRepository.findByUuid(uuid).orElse(null)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Offender not found: $uuid")

    if (offender.status != OffenderStatus.INACTIVE) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only INACTIVE offenders can be reactivated.")
    }

    val contactDetails = try {
      ndiliusApiClient.getContactDetails(offender.crn)
        ?: throw Exception("NDelius returned null contact details")
    } catch (e: Exception) {
      LOGGER.error("Failed to fetch contact details from NDelius for CRN: ${offender.crn}", e)
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Could not verify contact details in NDelius for ${offender.crn}.",
      )
    }

    // Don't reactivate a POP who is no longer eligible for online check-ins (in reset, or no active
    // events). Otherwise reactivation would send a check-in invite that the daily job then undoes.
    checkinIneligibilityReason(offender, contactDetails)?.let { reason ->
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Cannot reactivate ${offender.crn}: ${reason.description}",
      )
    }

    val requestedPreference = request.contactPreference?.contactPreference ?: offender.contactPreference

    when (requestedPreference) {
      ContactPreference.PHONE -> {
        if (contactDetails.mobile.isNullOrBlank()) {
          throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot reactivate: ${offender.crn} does not have a mobile number in NDelius.")
        }
      }
      ContactPreference.EMAIL -> {
        if (contactDetails.email.isNullOrBlank()) {
          throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot reactivate: ${offender.crn} does not have an email address in NDelius.")
        }
      }
    }

    request.checkinSchedule?.let { schedule ->
      validate(schedule)
      offender.firstCheckin = schedule.firstCheckin
      offender.checkinInterval = schedule.checkinInterval.duration
    }
    request.contactPreference?.let { pref ->
      offender.contactPreference = pref.contactPreference
    }

    val (savedOffender, setupId) = offenderSetupService.activateOffenderAndIncrementSetupCounter(offender)
    notificationService.sendReactivationCompletedNotifications(savedOffender, contactDetails, setupId)

    // only create a check in if the first check in date is set to today, otherwise cron job will handle creation
    val today = clock.today()
    if (savedOffender.firstCheckin == today) {
      // it's unlikely that there will be an existing check in because check ins become cancelled when PoPs are deactivated but we check in case
      val existingCheckin = checkinRepository.findByOffenderAndDueDate(savedOffender, today)
      val checkinExists = existingCheckin.isPresent && existingCheckin.get().status == CheckinStatus.CREATED

      if (!checkinExists) {
        checkinCreationService.createCheckin(
          offenderUuid = savedOffender.uuid,
          dueDate = today,
          createdBy = request.requestedBy,
        )
      } else {
        LOGGER.info("Check-in already exists for CRN ${savedOffender.crn}. Skipping creation.")
      }
    }

    LOGGER.info(
      "Reactivated offender: uuid={}, crn={}, requestedBy={}, reason={}",
      uuid,
      offender.crn,
      request.requestedBy,
      request.reason,
    )

    recordOffenderAuditEvent(OffenderAuditEventType.OFFENDER_REACTIVATED, savedOffender, request.reason)
    return ResponseEntity.ok(savedOffender.toSummaryDto(getOffenderPhotoUrl(savedOffender)))
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Update offender details",
    description = """Updates offender details. All fields need to be set to their desired value 
        (as in, no partial updates are allowed)
        
        Updating the check in schedule settings may trigger a notification if the new first check in date
        is *today*.""",
  )
  @ApiResponse(responseCode = "200", description = "Offender details updated")
  @ApiResponse(responseCode = "204", description = "No update required")
  @ApiResponse(responseCode = "400", description = "Can't complete operation due to offender status or invalid input")
  @ApiResponse(responseCode = "404", description = "Offender not found")
  @PostMapping("/{uuid}/update_details")
  fun updateDetails(
    @Parameter(description = "Offender UUID", required = true) @PathVariable uuid: UUID,
    @Valid @RequestBody request: OffenderDetailsUpdateRequest,
  ): ResponseEntity<OffenderSummaryDto> {
    val offender = offenderRepository.findByUuid(uuid).orElse(null)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Offender not found: $uuid")

    if (offender.status == OffenderStatus.INACTIVE) {
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Cannot update offender with status ${offender.status}. Only offenders with status INITIAL | VERIFIED can be updated.",
      )
    }

    val offenderBefore = offender.toSummaryDto()

    if (request.checkinSchedule != null) {
      validate(request.checkinSchedule)
      val scheduleUpdate = request.checkinSchedule
      offender.firstCheckin = scheduleUpdate.firstCheckin
      offender.checkinInterval = scheduleUpdate.checkinInterval.duration
      offender.updatedAt = clock.instant()
    }

    if (request.contactPreference != null) {
      val preferenceUpdate = request.contactPreference
      if (offender.contactPreference != preferenceUpdate.contactPreference) {
        offender.contactPreference = preferenceUpdate.contactPreference
        offender.updatedAt = clock.instant()
      }
    }

    LOGGER.info("Update offender details, CRN={}, updates: schedule={}, contact prefs?={}", offender.crn, request.checkinSchedule ?: "No update", request.contactPreference ?: "No update")
    if (request.checkinSchedule != null || request.contactPreference != null) {
      val saved = offenderRepository.save(offender)
      val offenderAfter = saved.toSummaryDto()
      if (request.checkinSchedule != null && newFirstCheckinDateIsToday(offenderBefore, offenderAfter, LocalDate.now(clock))) {
        LOGGER.debug("Creating check-in for offender {} as first check-in date is today", offenderAfter.uuid)
        checkinCreationService.createCheckin(offenderAfter.uuid, offenderAfter.firstCheckin, request.checkinSchedule.requestedBy)
      }
      return ResponseEntity.ok(offenderAfter)
    } else {
      return ResponseEntity.noContent().build()
    }
  }

  private fun recordOffenderAuditEvent(eventType: OffenderAuditEventType, offender: Offender, reason: String, sensitive: Boolean = false) {
    assert(eventType in listOf(OffenderAuditEventType.OFFENDER_DEACTIVATED, OffenderAuditEventType.OFFENDER_REACTIVATED))
    var contactDetails: ContactDetails? = null
    try {
      contactDetails = ndiliusApiClient.getContactDetails(offender.crn)
    } catch (e: Exception) {
      // exception already logged and sanitised elswhere
      LOGGER.info("Failed to get contact details for offender ${offender.crn} from NDelius. Using missing details instead.")
    }
    eventAuditService.recordOffenderEvent(eventType, offender, contactDetails, reason, sensitive)
  }

  private fun validate(scheduleUpdate: CheckinScheduleUpdateRequest) {
    if (scheduleUpdate.firstCheckin.isBefore(LocalDate.now(clock))) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "First check-in date cannot be in the past")
    }
  }

  private fun getOffenderPhotoUrl(offender: Offender): String? {
    if (offender.status == OffenderStatus.INITIAL) {
      return null
    }
    val url = s3UploadService.getOffenderPhoto(offender)
    if (url == null) {
      LOGGER.info("Photo not found in S3 for offender crn={}, uuid={}", offender.crn, offender.uuid)
    }
    return url?.toString()
  }

  companion object {
    private val LOGGER = logger<OffenderResource>()
  }
}

/**
 * Subset of [ContactDetails] that is returned in the summary DTO.
 */
data class OffenderSummaryDetails(
  override val name: Name,
) : INamedPerson

/** Simple DTO for offender lookup - no PII by default */
data class OffenderSummaryDto(
  val uuid: UUID,
  val crn: String,
  val status: OffenderStatus,
  val firstCheckin: LocalDate,
  val checkinInterval: CheckinInterval,
  val contactPreference: ContactPreference,
  val photoUrl: String? = null,
  val details: OffenderSummaryDetails? = null,
)

private fun Offender.toSummaryDto(photoUrl: String? = null, contactDetails: ContactDetails? = null) = OffenderSummaryDto(
  uuid = uuid,
  crn = crn,
  status = status,
  firstCheckin = firstCheckin,
  checkinInterval = CheckinInterval.fromDuration(checkinInterval),
  contactPreference = contactPreference,
  photoUrl = photoUrl,
  details = contactDetails?.let { OffenderSummaryDetails(it.name) },
)

data class OffenderHeaderDetails(
  val crn: String,
  val dateOfBirth: LocalDate,
  val tierScore: String,
  val tierDetailsLink: String,
  val overallRisk: String,
)

/** Request to deactivate an offender */
data class DeactivateOffenderRequest(
  @Schema(description = "Practitioner ID who requested the deactivation", required = true)
  @field:NotBlank
  val requestedBy: ExternalUserId,

  @Schema(description = "Reason for deactivation", required = true)
  @field:NotBlank
  val reason: String,

  @JsonDeserialize(using = StrictBooleanDeserializer::class)
  @Schema(description = "Whether the deactivation reason contains sensitive information", required = false)
  val sensitive: Boolean = false,
)

/** Request to reactivate an offender */
data class ReactivateOffenderRequest(
  @Schema(description = "Practitioner ID who requested the reactivation", required = true)
  @field:NotBlank
  val requestedBy: ExternalUserId,

  @Schema(description = "Reason for reactivation", required = true)
  @field:NotBlank
  val reason: String,

  val checkinSchedule: CheckinScheduleUpdateRequest? = null,
  val contactPreference: ContactPreferenceUpdateRequest? = null,
)

/** Request to update offender check in schedule */
data class CheckinScheduleUpdateRequest(
  @field:Schema(description = "Id of the user requesting the change", required = true)
  val requestedBy: ExternalUserId,
  @field:JsonDeserialize(using = uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocalDateDeserializer::class) val firstCheckin: LocalDate,
  val checkinInterval: CheckinInterval,
)

/** Request to update offender contact details */
data class ContactPreferenceUpdateRequest(
  @Schema(description = "Id of the user requesting the change", required = true)
  val requestedBy: ExternalUserId,
  val contactPreference: ContactPreference,
)

/**
 * Container for various offender details updates.
 *
 * Note: try grouping related details into a single data class (like schedule update)
 * and avoid adding top level fields for random bits of information. This will
 * make it clear what the semantics of the update is/should be and make validation easier.
 */
data class OffenderDetailsUpdateRequest(
  val checkinSchedule: CheckinScheduleUpdateRequest? = null,
  val contactPreference: ContactPreferenceUpdateRequest? = null,
)

private fun newFirstCheckinDateIsToday(
  beforeChange: OffenderSummaryDto,
  afterChange: OffenderSummaryDto,
  today: LocalDate,
): Boolean = beforeChange.firstCheckin != afterChange.firstCheckin && afterChange.firstCheckin == today

/**
 * Used only for audit events. We should log audit events if we have the CRN, but were unable
 * to get the full details from Ndelius for some reason
 */
// private fun missingDetails(crn: String) = ContactDetails(crn, Name(forename = "missing", surname = "missing"))
