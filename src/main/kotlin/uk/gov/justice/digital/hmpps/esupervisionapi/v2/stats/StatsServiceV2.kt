package uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.StatsSummaryRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.StatsSummary

@Service
class StatsServiceV2(
  private val repository: StatsSummaryRepository,
) {

  @Transactional(readOnly = true)
  fun getStats(): StatsSummary =
    repository.findBySingleton(1)
      ?: throw IllegalStateException(
        "Stats summary not found – materialised view stats_summary_v1 is empty"
      )
}
