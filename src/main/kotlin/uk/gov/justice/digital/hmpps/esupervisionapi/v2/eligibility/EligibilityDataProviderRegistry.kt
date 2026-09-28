package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.stereotype.Component

/** Resolves an [OffenderEligibilityRule.source] string to the [EligibilityDataProvider] that fetches it. */
@Component
class EligibilityDataProviderRegistry(
  providers: List<EligibilityDataProvider>,
) {
  private val bySource: Map<String, EligibilityDataProvider> = providers.associateBy { it.sourceKey }

  fun get(source: String): EligibilityDataProvider = bySource[source] ?: throw IllegalStateException("No eligibility data provider registered for source=$source")
}
