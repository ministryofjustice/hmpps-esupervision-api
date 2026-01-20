package uk.gov.justice.digital.hmpps.esupervisionapi.v2.feedback

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Constraint
import jakarta.validation.Payload
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus.CREATED
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Feedback
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.feedback.FeedbackService
import java.time.Instant
import kotlin.reflect.KClass

@RestController
@RequestMapping("/v2/feedback", produces = ["application/json"])
@Tag(name = "Feedback", description = "Endpoints for submitting and retrieving feedback")
class FeedbackResource(private val service: FeedbackService) {

  private val logger = LoggerFactory.getLogger(FeedbackResource::class.java)

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(
    summary = "Submit feedback",
    description = "Create a new feedback row. Expects JSON with the feedback questions.",
  )
  @ApiResponse(responseCode = "201", description = "Feedback saved successfully")
  @ApiResponse(responseCode = "400", description = "Invalid request: feedback payload is missing the required 'version' field or contains invalid values")
  @PostMapping
  fun submitFeedback(@Valid @RequestBody request: FeedbackRequest): ResponseEntity<FeedbackResponse> {
    val saved = service.createFeedback(request.feedback)
    logger.info("Saved feedback with id={}", saved.id)
    return ResponseEntity.status(CREATED).body(saved.toResponse())
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @Operation(summary = "Get all feedback (paginated)", description = "Returns all feedback rows with optional pagination and sorting.")
  @ApiResponse(responseCode = "200", description = "Page of feedback returned")
  @GetMapping
  fun getAllFeedback(pageable: Pageable): ResponseEntity<Page<FeedbackResponse>> {
    val page: Page<FeedbackResponse> = service.getAllFeedback(pageable).map { it.toResponse() }
    logger.info("Retrieved page {} of feedback with {} entries", page.number, page.size)
    return ResponseEntity.ok(page)
  }
}

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [FeedbackVersionValidator::class])
annotation class RequiresVersion(
  val message: String = "feedback must contain a version field",
  val groups: Array<KClass<*>> = [],
  val payload: Array<KClass<out Payload>> = [],
)

/** Request DTO for creating feedback */
data class FeedbackRequest(
  @field:NotNull
  @field:NotEmpty
  @field:RequiresVersion
  val feedback: Map<String, Any>,
)

/** Response DTO for feedback */
data class FeedbackResponse(val id: Long, val feedback: Map<String, Any>, val createdAt: Instant)

private fun Feedback.toResponse() = FeedbackResponse(id = id, feedback = feedback, createdAt = createdAt)
