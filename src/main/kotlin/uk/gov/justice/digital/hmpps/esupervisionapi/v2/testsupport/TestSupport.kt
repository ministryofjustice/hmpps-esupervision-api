package uk.gov.justice.digital.hmpps.esupervisionapi.v2.testsupport

/**
 * Test support beans exist only when the flag is on *and* the app is running as dev. The flag is set
 * in values-dev.yaml alone; requiring `app.env` as well means setting it anywhere else by mistake
 * still creates nothing, so the endpoints 404 there even with a valid token.
 */
const val TEST_SUPPORT_ENABLED =
  "\${app.test-support.enabled:false} and '\${app.env:}'.equalsIgnoreCase('DEV')"

val CRN_PATTERN = Regex("^[A-Z][0-9]{6}$")
