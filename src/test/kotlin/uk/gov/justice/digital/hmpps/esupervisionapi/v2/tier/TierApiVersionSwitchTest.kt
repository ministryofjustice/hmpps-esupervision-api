package uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class TierApiVersionSwitchTest {

  /** Production's switch-over: midnight on 1 October 2026, UK time (BST). */
  private val productionV3From = "2026-10-01T00:00:00+01:00"

  private fun switchAt(now: String, v3From: String) = TierApiVersionSwitch(v3From, Clock.fixed(Instant.parse(now), ZoneId.of("Europe/London")))

  @Test
  fun `stays on v2 when no switch-over is configured`() {
    assertEquals(TierApiVersion.V2, switchAt("2030-01-01T00:00:00Z", "").current())
  }

  @Test
  fun `stays on v2 until the last moment of 30 September UK time`() {
    assertEquals(TierApiVersion.V2, switchAt("2026-09-30T22:59:59.999Z", productionV3From).current())
  }

  @Test
  fun `moves to v3 at midnight UK time on 1 October, which is 23 00 UTC the day before`() {
    assertEquals(TierApiVersion.V3, switchAt("2026-09-30T23:00:00Z", productionV3From).current())
    assertEquals(TierApiVersion.V3, switchAt("2026-10-02T12:00:00Z", productionV3From).current())
  }
}
