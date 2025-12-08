package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventDetailResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import java.util.UUID

/** V2 Event Detail Endpoints Callback URLs for Ndilius to query event details */
@RestController
@RequestMapping("/v2/events")
@Tag(name = "V2 Events", description = "Event detail callback endpoints for Ndilius")
class EventV2Resource(
  private val notificationService: NotificationV2Service,
) {
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__CHECK_IN__RO')")
  @GetMapping("/setup-completed/{uuid}")
  @Operation(
    summary = "Get setup completed event details",
    description =
    "Callback URL for Ndilius to query formatted notes for setup completed event",
  )
  fun getSetupCompletedEvent(
    @Parameter(description = "Offender UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<EventDetailResponse> {
    val detailUrl = "/v2/events/setup-completed/$uuid"
    val event =
      notificationService.getEventDetail(detailUrl)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    return ResponseEntity.ok(event)
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__CHECK_IN__RO')")
  @GetMapping("/checkin-created/{uuid}")
  @Operation(
    summary = "Get checkin created event details",
    description =
    "Callback URL for Ndilius to query formatted notes for checkin created event",
  )
  fun getCheckinCreatedEvent(
    @Parameter(description = "Checkin UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<EventDetailResponse> {
    val detailUrl = "/v2/events/checkin-created/$uuid"
    val event =
      notificationService.getEventDetail(detailUrl)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    return ResponseEntity.ok(event)
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__CHECK_IN__RO')")
  @GetMapping("/checkin-submitted/{uuid}")
  @Operation(
    summary = "Get checkin submitted event details",
    description =
    "Callback URL for Ndilius to query formatted notes for checkin submitted event",
  )
  fun getCheckinSubmittedEvent(
    @Parameter(description = "Checkin UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<EventDetailResponse> {
    val detailUrl = "/v2/events/checkin-submitted/$uuid"
    val event =
      notificationService.getEventDetail(detailUrl)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    return ResponseEntity.ok(event)
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__CHECK_IN__RO')")
  @GetMapping("/checkin-reviewed/{uuid}")
  @Operation(
    summary = "Get checkin reviewed event details",
    description =
    "Callback URL for Ndilius to query formatted notes for checkin reviewed event",
  )
  fun getCheckinReviewedEvent(
    @Parameter(description = "Checkin UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<EventDetailResponse> {
    val detailUrl = "/v2/events/checkin-reviewed/$uuid"
    val event =
      notificationService.getEventDetail(detailUrl)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    return ResponseEntity.ok(event)
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__CHECK_IN__RO')")
  @GetMapping("/checkin-expired/{uuid}")
  @Operation(
    summary = "Get checkin expired event details",
    description =
    "Callback URL for Ndilius to query formatted notes for checkin expired event",
  )
  fun getCheckinExpiredEvent(
    @Parameter(description = "Checkin UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<EventDetailResponse> {
    val detailUrl = "/v2/events/checkin-expired/$uuid"
    val event =
      notificationService.getEventDetail(detailUrl)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    return ResponseEntity.ok(event)
  }
}
