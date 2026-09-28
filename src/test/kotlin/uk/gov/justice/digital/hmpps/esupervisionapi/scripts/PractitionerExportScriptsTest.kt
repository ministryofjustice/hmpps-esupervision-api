package uk.gov.justice.digital.hmpps.esupervisionapi.scripts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs scripts/test/practitioner_export_test.sh, the tests for the practitioner export's shell
 * scripts, so they run with the rest of the suite rather than only when someone remembers.
 *
 * The harness needs jq, curl, python3 and node. Where any is missing it exits 2 and this test is
 * skipped rather than failed: the scripts are an operational tool, and an agent without node
 * should not break the build. Run the harness directly to see why it skipped.
 */
class PractitionerExportScriptsTest {

  @Test
  fun `practitioner export scripts pass their tests`() {
    val process = ProcessBuilder("bash", HARNESS.path)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(5, TimeUnit.MINUTES)) {
      process.destroyForcibly()
      throw AssertionError("harness timed out after 5 minutes:\n$output")
    }

    if (process.exitValue() == SKIPPED) abort<Unit>("harness prerequisites missing: ${output.trim()}")
    assertThat(process.exitValue()).withFailMessage { "harness failed:\n$output" }.isZero()
  }

  companion object {
    private val HARNESS = File("scripts/test/practitioner_export_test.sh")
    private const val SKIPPED = 2
  }
}
