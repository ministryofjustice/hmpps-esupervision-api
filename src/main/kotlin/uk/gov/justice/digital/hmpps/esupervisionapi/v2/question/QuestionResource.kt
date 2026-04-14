package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.BindingResult
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.intoResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AssignCustomQuestionsRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AssignCustomQuestionsResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Language
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ListQuestionTemplatesResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderQuestionList
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.UpcomingOffenderQuestions
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.UpcomingQuestionItemsResponse

@Validated
@RestController
@RequestMapping("/v2/questions")
@Tag(name = "Checkin Questions", description = "Manage checkin questions")
class QuestionResource(
  private val questionService: QuestionService,
) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/templates")
  @Tag(name = "practitioner")
  @Operation(
    summary = "List available question templates",
    description = "Retrieve list of question templates in the given language.",
  )
  @ApiResponse(responseCode = "200", description = "Checkins retrieved successfully")
  fun listQuestionTemplates(@Parameter(description = "en-GB or cy-GB") @RequestParam language: Language): ResponseEntity<ListQuestionTemplatesResponse> = ResponseEntity.ok(ListQuestionTemplatesResponse(questionService.listQuestionTemplates(language)))

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/question-list/{listId}")
  @Tag(name = "offender")
  @Operation(
    summary = "Get question list for the offender",
    description = "The returned questions are already processed and ready to be displayed to the offender.",
  )
  fun getQuestionList(
    @Parameter(required = true) @PathVariable(required = true) listId: Long,
    @RequestParam(required = true) @Valid language: Language,
    binding: BindingResult,
  ): ResponseEntity<OffenderQuestionList> {
    if (binding.hasErrors()) {
      throw intoResponseStatusException(binding)
    }

    return ResponseEntity.ok(questionService.offenderQuestionList(listId, language))
  }

  /**
   * .
   *
   */
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/upcoming/{crn}/question-items")
  @Tag(name = "practitioner")
  @Operation(
    summary = "Get upcoming question items for the offender",
    description = """Assuming the offender with given CRN is VERIFIED, this endpoint will always return valid question items (templates + params), 
      including the fixed questions.
      These are meant to be used in the practitioner context.
    """,
  )
  fun upcomingQuestionItems(@PathVariable crn: String, @RequestParam(required = true) @Valid language: Language): ResponseEntity<UpcomingQuestionItemsResponse> {
    val upcoming = questionService.upcomingQuestionListItems(crn, language)
    return ResponseEntity.ok(UpcomingQuestionItemsResponse(upcoming))
  }

  /**
   * Assuming the CRN is VERIFIED, this endpoint will always return valid questions for an offender
   * (including the fixed questions). These are ready to be displayed to the offender.
   */
  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/upcoming/{crn}/offender-questions")
  @Tag(name = "offender")
  @Operation(
    summary = "Get upcoming questions for the offender",
    description = """
      Assuming the CRN is VERIFIED, this endpoint will always return valid questions for an offender (including the fixed questions). 
      These are meant be used in the offender context.
    """,
  )
  fun upcomingOffenderQuestions(@PathVariable crn: CRN, @RequestParam(required = true) @Valid language: Language): ResponseEntity<UpcomingOffenderQuestions> {
    val upcoming = questionService.upcomingQuestionListItems(crn, language)
    val upcomingQuestions = upcoming.items.map { it.evalTemplate() }
    return ResponseEntity.ok(UpcomingOffenderQuestions(upcoming.expectedCheckinDate, upcomingQuestions))
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @PutMapping("/assignment")
  @Tag(name = "practitioner")
  @Operation(summary = "Assign custom questions to an offender")
  @ApiResponse(responseCode = "200", description = "Questions assigned successfully")
  @ApiResponse(responseCode = "422", description = "It's day of the checkin and checkin hasn't been created (and invite sent) yet")
  @ApiResponse(
    responseCode = "422",
    description = """It's day of the checkin and checkin has been sent but hasn't been submitted yet. 
    Next assignment possible once checkin has been submitted.""",
  )
  fun assignCustomQuestions(
    @RequestParam crn: CRN,
    @RequestBody @Valid request: AssignCustomQuestionsRequest,
    binding: BindingResult,
  ): ResponseEntity<AssignCustomQuestionsResponse> {
    if (binding.hasErrors()) {
      throw intoResponseStatusException(binding)
    }

    return ResponseEntity.ok(questionService.assignCustomQuestions(crn, request))
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @DeleteMapping("/assignment")
  @Tag(name = "practitioner")
  @Operation(
    summary = "Delete upcoming question assignment",
    description = """
      
    """,
  )
  @ApiResponse(responseCode = "200", description = "Assignment deleted successfully")
  fun deleteUpcomingAssignment(@RequestParam crn: CRN): ResponseEntity<Map<String, String>> {
    // TODO: should we also disallow on day of checkin?
    val body = if (questionService.deleteUpcomingAssignment(crn)) mapOf("message" to "deleted") else mapOf("message" to "nothing to delete")
    return ResponseEntity.ok(body)
  }
}
