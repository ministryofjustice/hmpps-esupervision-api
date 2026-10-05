package uk.gov.justice.digital.hmpps.esupervisionapi.scripts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs scripts/test/tier_counts_test.sh, the tests for scripts/run_tier_counts.sh, so they run
 * with the rest of the suite rather than only when someone remembers.
 *
 * The harness needs jq, curl, python3 and node. Where any is missing it exits 2 and this test is
 * skipped rather than failed: the script is an operational tool, and an agent without node
 * should not break the build. Run the harness directly to see why it skipped.
 */
class TierCountsScriptTest {

  @Test
  fun `tier counts script passes its tests`() {
    // The harness's mktemp -d lands here, so its files go even if it is killed before its own
    // EXIT trap runs.
    val tmp = Files.createTempDirectory("script-harness").toFile()
    try {
      runHarness(tmp)
    } finally {
      tmp.deleteRecursively()
    }
  }

  private fun runHarness(tmp: File) {
    // Output goes to a file, not a pipe read here: reading a pipe to EOF would block until the
    // harness exits, so a hung harness would hang the build and the timeout would never fire.
    val log = File.createTempFile("tier-counts-test", ".log").apply { deleteOnExit() }
    val process = ProcessBuilder("bash", HARNESS.path)
      .redirectErrorStream(true)
      .redirectOutput(log)
      .apply { environment()["TMPDIR"] = tmp.path }
      .start()
    val finished = process.waitFor(5, TimeUnit.MINUTES)
    if (!finished) {
      // Killing bash alone would orphan the stub server and any fake port-forward: take the
      // descendants first, while they are still reachable through it.
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
    private val HARNESS = File("scripts/test/tier_counts_test.sh")
    private const val SKIPPED = 2
  }
}
