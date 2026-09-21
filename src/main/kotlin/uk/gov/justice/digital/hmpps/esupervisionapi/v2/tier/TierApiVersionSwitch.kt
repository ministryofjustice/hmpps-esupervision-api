package uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import java.time.Clock
import java.time.OffsetDateTime

enum class TierApiVersion(val tierPath: String, val uiCasePath: String) {
  V2("/v2/crn/{crn}/tier", "/case/{crn}"),
  V3("/v3/crn/{crn}/tier", "/v3/case/{crn}"),
}

/**
 * ESUP-2186: moves the Tier API from v2 to v3 at a configured moment rather than on a deploy, so
 * production can switch at midnight on 1 October 2026 without anyone releasing at midnight.
 *
 * Evaluated per call against [clock]. A blank value keeps v2 indefinitely; a past value is v3 now.
 * Once v3 is live everywhere, delete this and hard-code [TierApiVersion.V3].
 */
@Component
class TierApiVersionSwitch(
  @Value("\${app.features.esup-2186-tier-v3-from:}") v3From: String,
  private val clock: Clock,
) {
  private val v3FromInstant = v3From.takeIf { it.isNotBlank() }?.let { OffsetDateTime.parse(it).toInstant() }

  init {
    LOG.info("Tier API v3 from: {}", v3FromInstant ?: "never (v2)")
  }

  fun current(): TierApiVersion = if (v3FromInstant != null && !clock.instant().isBefore(v3FromInstant)) {
    TierApiVersion.V3
  } else {
    TierApiVersion.V2
  }

  companion object {
    private val LOG = logger<TierApiVersionSwitch>()
  }
}
