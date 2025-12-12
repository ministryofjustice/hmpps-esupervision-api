package uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain

enum class OffenderStatus {
  INITIAL,
  VERIFIED,
  INACTIVE,
  ;

  fun canTransitionTo(newStatus: OffenderStatus): Boolean = when (this) {
    INITIAL -> true
    VERIFIED -> newStatus != INITIAL
    INACTIVE -> false
  }
}
