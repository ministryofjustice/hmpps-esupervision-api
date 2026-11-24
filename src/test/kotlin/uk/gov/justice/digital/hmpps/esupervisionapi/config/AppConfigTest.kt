package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AppConfigTest {

  @ParameterizedTest(name = "ESUP_2082={0}, sync dry run={1} -> sync job stops people={2}")
  @CsvSource(
    "false, false, false",
    "false, true,  false",
    "true,  true,  false",
    "true,  false, true",
  )
  fun `the eligibility sync job only stops people with ESUP_2082 on and not as a dry run`(
    esup2082: Boolean,
    syncDryRun: Boolean,
    syncJobStopsPeople: Boolean,
  ) {
    val config = AppConfig(
      checkinUrl = "https://checkin",
      mpopUrl = "https://mpop",
      hostedAt = "https://api",
      esup1239ProxyLinks = false,
      esup1763RemoveSnapshots = false,
      esup2082NewEligibilityRules = esup2082,
      esup2305DeliusEventSelection = false,
      esup2223UpcomingCheckins = false,
      eligibilitySyncDryRun = syncDryRun,
    )

    assertThat(config.eligibilitySyncStopsPeople).isEqualTo(syncJobStopsPeople)
  }
}
