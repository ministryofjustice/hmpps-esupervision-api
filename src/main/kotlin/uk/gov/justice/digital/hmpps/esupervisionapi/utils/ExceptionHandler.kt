package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.InvalidStateTransitionException

data class ErrorResponse(
  val status: Int,
  val message: String,
)

@ControllerAdvice
class ExceptionHandler {

  @ExceptionHandler(BadArgumentException::class)
  fun handleBadArgumentException(e: BadArgumentException): ResponseEntity<ErrorResponse> {
    val response = ErrorResponse(
      HttpStatus.BAD_REQUEST.value(),
      message = e.message ?: "Bad Request",
    )
    return ResponseEntity(response, HttpStatus.BAD_REQUEST)
  }

  @ExceptionHandler(InvalidStateTransitionException::class)
  fun handleInvalidStateTransitionException(e: InvalidStateTransitionException): ResponseEntity<ErrorResponse> {
    val response = ErrorResponse(
      HttpStatus.BAD_REQUEST.value(),
      message = e.message ?: "Bad Request",
    )
    return ResponseEntity(response, HttpStatus.BAD_REQUEST)
  }

  @ExceptionHandler(ResourceNotFoundException::class)
  fun handleResourceNotFoundException(e: ResourceNotFoundException): ResponseEntity<ErrorResponse> {
    val response = ErrorResponse(
      HttpStatus.NOT_FOUND.value(),
      message = e.message ?: "Not Found",
    )
    return ResponseEntity(response, HttpStatus.NOT_FOUND)
  }

  @ExceptionHandler(Exception::class)
  fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
    LOG.error("Unexpected exception", ex)
    val errorResponse = ErrorResponse(
      status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
      message = "Unexpected Error",
    )
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
  }

  companion object {
    val LOG = LoggerFactory.getLogger(this::class.java)
  }
}
