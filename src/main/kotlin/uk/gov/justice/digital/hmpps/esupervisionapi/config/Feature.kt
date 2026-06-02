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

  /**
   * ESUP-1672: Require a SHA-256 content hash when requesting presigned upload URLs
   */
  ESUP_1672_REQUIRE_UPLOAD_CONTENT_HASH,

  /**
   * ESUP-1763: Remove snapshots from checkins if the manual check confirms it is a match
   */
  ESUP_1763,
}
