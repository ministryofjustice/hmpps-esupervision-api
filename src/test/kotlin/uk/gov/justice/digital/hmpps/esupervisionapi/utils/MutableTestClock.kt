package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@TestConfiguration
class TestClockConfiguration {
  @Bean
  @Primary // This ensures it overrides the production Clock bean
  fun testClock(): MutableTestClock = MutableTestClock()
}

class MutableTestClock(
  private var delegate: Clock = Clock.fixed(Instant.parse("2026-03-31T10:00:00Z"), ZoneId.of("Europe/London")),
) : Clock() {
  override fun getZone(): ZoneId = delegate.zone
  override fun withZone(zone: ZoneId): Clock = MutableTestClock(delegate.withZone(zone))
  override fun instant(): Instant = delegate.instant()

  fun advanceBy(duration: Duration) {
    delegate = offset(delegate, duration)
  }

  fun advanceTo(instant: Instant) {
    delegate = fixed(instant, delegate.zone)
  }
}
