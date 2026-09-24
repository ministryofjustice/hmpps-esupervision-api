package uk.gov.justice.digital.hmpps.esupervisionapi.v2

/**
 * Specifying the use case for an API call can help the client choose
 * options for that particular use case (e.g., request timeouts)
 */
enum class ApiUseCase {
  GENERAL,
  ELIGIBILITY_CHECK,
}
