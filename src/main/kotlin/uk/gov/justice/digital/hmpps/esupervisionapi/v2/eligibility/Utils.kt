package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EligibilityAnswers
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import java.util.concurrent.CompletableFuture

fun EligibilityAnswers?.eligibilityData(): Map<String, Any?> = mapOf(
  "DEVICE_RESTRICTION" to this?.deviceRestriction,
  "ACCREDITED_PROGRAMME" to this?.accreditedProgramme,
  "YOUTH_SENTENCE" to this?.youthSentence,
)

/**
 * Creates a cache pre-filled with eligibility data from NDELIUS, ESUP and practitioners (manual).
 */
fun makeCache(offender: Offender, contactDetails: ContactDetails, answers: EligibilityAnswers?): Map<String, CompletableFuture<Map<String, Any?>>> {
  val m = mutableMapOf(
    "NDELIUS" to CompletableFuture.completedFuture(contactDetails.eligibilityData()),
    "ESUP" to CompletableFuture.completedFuture(offender.eligibilityData()),
  )
  if (answers != null) {
    m["MANUAL"] = CompletableFuture.completedFuture(answers.eligibilityData())
  }

  return m
}
