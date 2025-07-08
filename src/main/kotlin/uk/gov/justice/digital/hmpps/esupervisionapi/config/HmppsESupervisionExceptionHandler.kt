package uk.gov.justice.digital.hmpps.esupervisionapi.config

import jakarta.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus.BAD_REQUEST
import org.springframework.http.HttpStatus.FORBIDDEN
import org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
import org.springframework.http.HttpStatus.NOT_FOUND
import org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.InvalidOffenderSetupState
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.InvalidStateTransitionException
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.MissingVideoException
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.BadArgumentException
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

@RestControllerAdvice
class HmppsESupervisionExceptionHandler {

  @ExceptionHandler(MissingVideoException::class)
  fun handleMissingVideoException(e: MissingVideoException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(UNPROCESSABLE_ENTITY)
    .body(
      ErrorResponse(
        UNPROCESSABLE_ENTITY,
        userMessage = "Checkin submission requires a video upload",
        developerMessage = "No video found for given checkin",
      ),
    )

  @ExceptionHandler(ResponseStatusException::class)
  fun handleResponseStatusException(e: ResponseStatusException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(e.statusCode)
    .body(
      ErrorResponse(
        status = e.statusCode.value(),
        userMessage = e.message,
        developerMessage = e.message,
      ),
    )

  @ExceptionHandler(InvalidOffenderSetupState::class)
  fun handleInvalidOffenderSetupState(exception: InvalidOffenderSetupState): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(UNPROCESSABLE_ENTITY)
    .body(
      ErrorResponse(
        status = UNPROCESSABLE_ENTITY,
        userMessage = exception.message,
        developerMessage = exception.message,
      ),
    ).also {
      log.info("Could not proceed with operation, setup is incomplete: {}", exception.message)
    }

  @ExceptionHandler(InvalidStateTransitionException::class)
  fun handleInvalidStateTransitionException(e: InvalidStateTransitionException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        userMessage = e.message ?: "Bad Request",
        developerMessage = "Attempted record modification is invalid (e.g. approving an expired invite etc).",
      ),
    ).also {
      log.info("Encountered an invalid state transition exception: {}", e.message)
    }

  @ExceptionHandler(ValidationException::class)
  fun handleValidationException(e: ValidationException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        userMessage = "Validation failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Validation exception: {}", e.message) }

  @ExceptionHandler(BadArgumentException::class)
  fun handleBadArgumentException(e: BadArgumentException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(UNPROCESSABLE_ENTITY)
    .body(
      ErrorResponse(
        status = UNPROCESSABLE_ENTITY,
        userMessage = "Unprocessable entity: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Bad argument exception: {}", e.message) }

  @ExceptionHandler(NoResourceFoundException::class)
  fun handleNoResourceFoundException(e: NoResourceFoundException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(NOT_FOUND)
    .body(
      ErrorResponse(
        status = NOT_FOUND,
        userMessage = "No resource found failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("No resource found exception: {}", e.message) }

  @ExceptionHandler(AccessDeniedException::class)
  fun handleAccessDeniedException(e: AccessDeniedException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(FORBIDDEN)
    .body(
      ErrorResponse(
        status = FORBIDDEN,
        userMessage = "Forbidden: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.debug("Forbidden (403) returned: {}", e.message) }

  @ExceptionHandler(Exception::class)
  fun handleException(e: Exception): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(INTERNAL_SERVER_ERROR)
    .body(
      ErrorResponse(
        status = INTERNAL_SERVER_ERROR,
        userMessage = "Unexpected error: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.error("Unexpected exception", e) }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
