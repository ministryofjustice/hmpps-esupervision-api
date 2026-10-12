package uk.gov.justice.digital.hmpps.esupervisionapi.scripts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs scripts/test/practitioner_tier_export_test.sh, the tests for
 * scripts/run_practitioner_tier_export.sh, so they run with the rest of the suite.
 *
 * Skipped rather than failed when the harness's prerequisites (jq, curl, python3, node) are
 * missing, as for [PopContactExportScriptTest].
 */
class PractitionerTierExportScriptTest {

  @Test
  fun `practitioner tier export script passes its tests`() {
    val tmp = Files.createTempDirectory("script-harness").toFile()
    try {
      runHarness(tmp)
    } finally {
      tmp.deleteRecursively()
    }
  }

  private fun runHarness(tmp: File) {
    // Output goes to a file, not a pipe read here, so a hung harness cannot block past the timeout.
    val log = File.createTempFile("practitioner-tier-export-test", ".log").apply { deleteOnExit() }
    val process = ProcessBuilder("bash", HARNESS.path)
      .redirectErrorStream(true)
      .redirectOutput(log)
      .apply { environment()["TMPDIR"] = tmp.path }
      .start()
    val finished = process.waitFor(5, TimeUnit.MINUTES)
    if (!finished) {
      process.descendants().forEach { it.destroyForcibly() }
      process.destroyForcibly()
      process.waitFor(10, TimeUnit.SECONDS)
    }
    val output = log.readText()
    if (!finished) throw AssertionError("harness timed out after 5 minutes:\n$output")

    if (process.exitValue() == SKIPPED) abort<Unit>("harness prerequisites missing: ${output.trim()}")
    assertThat(process.exitValue()).withFailMessage { "harness failed:\n$output" }.isZero()
  }

  companion object {
    private val HARNESS = File("scripts/test/practitioner_tier_export_test.sh")
    private const val SKIPPED = 2
  }
}
