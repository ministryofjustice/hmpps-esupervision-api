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
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotificationService
import java.util.UUID

/** V2 Event Detail Endpoints Callback URLs for Ndilius to query event details */
@RestController
@RequestMapping("/v2/events")
@Tag(name = "V2 Events", description = "Event detail callback endpoints for Ndilius")
class EventResource(
  private val notificationService: NotificationService,
) {
  @PreAuthorize("hasAnyRole('ROLE_ESUPERVISION__CHECK_IN__RO', 'ROLE_ESUPERVISION__ESUPERVISION_UI')")
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

  @PreAuthorize("hasAnyRole('ROLE_ESUPERVISION__CHECK_IN__RO', 'ROLE_ESUPERVISION__ESUPERVISION_UI')")
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

  @PreAuthorize("hasAnyRole('ROLE_ESUPERVISION__CHECK_IN__RO', 'ROLE_ESUPERVISION__ESUPERVISION_UI')")
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

  @PreAuthorize("hasAnyRole('ROLE_ESUPERVISION__CHECK_IN__RO', 'ROLE_ESUPERVISION__ESUPERVISION_UI')")
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

  @PreAuthorize("hasAnyRole('ROLE_ESUPERVISION__CHECK_IN__RO', 'ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/checkin-annotated/{uuid}")
  @Operation(
    summary = "Get checkin updated event details",
    description =
    "Callback URL for Ndilius to query formatted notes for checkin updated event",
  )
  fun getCheckinAnnotatedEvent(
    @Parameter(description = "Checkin or annotation UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<EventDetailResponse> {
    val detailUrl = "/v2/events/checkin-annotated/$uuid"
    val event =
      notificationService.getEventDetail(detailUrl)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    return ResponseEntity.ok(event)
  }

  @PreAuthorize("hasAnyRole('ROLE_ESUPERVISION__CHECK_IN__RO', 'ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/checkin-scheduled/{uuid}")
  @Operation(
    summary = "Get checkin scheduled event details",
    description =
    "Callback URL for Ndilius to query formatted notes for checkin scheduled event",
  )
  fun getCheckinScheduledEvent(
    @Parameter(description = "Event UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<EventDetailResponse> {
    val detailUrl = "/v2/events/checkin-scheduled/$uuid"
    val event =
      notificationService.getEventDetail(detailUrl)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    return ResponseEntity.ok(event)
  }

  @PreAuthorize("hasAnyRole('ROLE_ESUPERVISION__CHECK_IN__RO', 'ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/checkin-rescheduled/{uuid}")
  @Operation(
    summary = "Get checkin rescheduled event details",
    description =
    "Callback URL for Ndilius to query formatted notes for checkin rescheduled event",
  )
  fun getCheckinRescheduledEvent(
    @Parameter(description = "Event UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<EventDetailResponse> {
    val detailUrl = "/v2/events/checkin-rescheduled/$uuid"
    val event =
      notificationService.getEventDetail(detailUrl)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    return ResponseEntity.ok(event)
  }

  @PreAuthorize("hasAnyRole('ROLE_ESUPERVISION__CHECK_IN__RO', 'ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/checkin-cancelled/{uuid}")
  @Operation(
    summary = "Get checkin cancelled event details",
    description =
    "Callback URL for Ndilius to query formatted notes for checkin cancelled event",
  )
  fun getCheckinCancelledEvent(
    @Parameter(description = "Event UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<EventDetailResponse> {
    val detailUrl = "/v2/events/checkin-cancelled/$uuid"
    val event =
      notificationService.getEventDetail(detailUrl)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    return ResponseEntity.ok(event)
  }

  @PreAuthorize("hasAnyRole('ROLE_ESUPERVISION__CHECK_IN__RO', 'ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/checkin-link-delivered/{uuid}")
  @Operation(
    summary = "Get checkin link delivered event details",
    description =
    "Callback URL for Ndilius to query formatted notes for checkin link delivered event",
  )
  fun getCheckinLinkDeliveredEvent(
    @Parameter(description = "Event UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<EventDetailResponse> {
    val detailUrl = "/v2/events/checkin-link-delivered/$uuid"
    val event =
      notificationService.getEventDetail(detailUrl)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    return ResponseEntity.ok(event)
  }

  @PreAuthorize("hasAnyRole('ROLE_ESUPERVISION__CHECK_IN__RO', 'ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/checkin-link-not-delivered/{uuid}")
  @Operation(
    summary = "Get checkin link not delivered event details",
    description =
    "Callback URL for Ndilius to query formatted notes for checkin link not delivered event",
  )
  fun getCheckinLinkNotDeliveredEvent(
    @Parameter(description = "Event UUID", required = true) @PathVariable uuid: UUID,
  ): ResponseEntity<EventDetailResponse> {
    val detailUrl = "/v2/events/checkin-link-not-delivered/$uuid"
    val event =
      notificationService.getEventDetail(detailUrl)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    return ResponseEntity.ok(event)
  }
}
