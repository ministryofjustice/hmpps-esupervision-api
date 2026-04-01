package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.BindingResult
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.intoResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AssignCustomQuestionsRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.AssignCustomQuestionsResponse
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Language
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderQuestionList
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionTemplateDto

@Validated
@RestController
@RequestMapping("/v2/questions")
@Tag(name = "Checkin Questions", description = "Manage checkin questions")
class QuestionResource(
  private val questionService: QuestionService,
) {

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/templates")
  @Operation(
    summary = "List available question templates",
    description = "Retrieve list of question templates in the given language.",
  )
  @ApiResponse(responseCode = "200", description = "Checkins retrieved successfully")
  fun listQuestionTemplates(@Parameter(description = "en-GB or cy-GB") @RequestParam language: Language): ResponseEntity<ListQuestionTemplatesResponse> = ResponseEntity.ok(ListQuestionTemplatesResponse(questionService.listQuestionTemplates(language)))

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/question-list/{listId}")
  fun getQuestionList(
    @Parameter(required = true) @PathVariable(required = true) listId: Long,
    @RequestParam(required = true) @Valid language: Language,
    binding: BindingResult,
  ): OffenderQuestionList {
    if (binding.hasErrors()) {
      throw intoResponseStatusException(binding)
    }

    return questionService.offenderQuestionList(listId, language)
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @PutMapping("/question-list/assign")
  fun assignCustomQuestions(
    @RequestParam(required = true) crn: String,
    @RequestBody(required = true) @Valid request: AssignCustomQuestionsRequest,
    binding: BindingResult,
  ): ResponseEntity<AssignCustomQuestionsResponse> {
    if (binding.hasErrors()) {
      throw intoResponseStatusException(binding)
    }

    return ResponseEntity.ok(questionService.assignCustomQuestions(crn, request))
  }
}

data class ListQuestionTemplatesResponse(
  val templates: List<QuestionTemplateDto>,
)
