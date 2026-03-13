package uk.gov.justice.digital.hmpps.esupervisionapi.config

enum class Feature {
  /**
   * ESUP-1239: Provide proxy links to NDelius
   */
  ESUP_1239,

  /**
   * ESUP-1183: Ensure a POP is still on probation before sending them a check in link
   */
  ESUP_1183,
}
