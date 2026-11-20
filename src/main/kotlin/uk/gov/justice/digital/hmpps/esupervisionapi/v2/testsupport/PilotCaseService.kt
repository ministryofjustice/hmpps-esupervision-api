package uk.gov.justice.digital.hmpps.esupervisionapi.v2.testsupport

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository

data class PilotCaseRequest(
  /** Whether the person counts as having been in online check ins before the pilot cut-off. */
  val inPilot: Boolean,
)

data class PilotCaseResponse(
  val crn: String,
  val inPilot: Boolean,
  /** The practitioner who now has, or no longer has, this pilot case. */
  val practitionerId: String,
  val status: String,
)

/**
 * Marks a person as a pilot case (offender_v2.in_pilot), which no real journey can do since the
 * pilot cut-off has passed. For the e2e tests: a pilot person is eligible on their own, and a
 * practitioner with one - set up or allocated, and past INITIAL - may sign up Tier A to C people.
 */
@Service
@ConditionalOnExpression(TEST_SUPPORT_ENABLED)
class PilotCaseService(
  private val offenderRepository: OffenderRepository,
) {

  @Transactional
  fun setInPilot(rawCrn: String, inPilot: Boolean): PilotCaseResponse {
    val crn = rawCrn.trim().uppercase()
    if (!CRN_PATTERN.matches(crn)) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a valid CRN")
    }
    val offender = offenderRepository.findByCrn(crn)
      .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No person on probation with CRN $crn") }
    offender.inPilot = inPilot
    LOGGER.info("Test support: crn={} in_pilot={}", crn, inPilot)
    return PilotCaseResponse(crn, inPilot, offender.practitionerId, offender.status.name)
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(PilotCaseService::class.java)
  }
}
