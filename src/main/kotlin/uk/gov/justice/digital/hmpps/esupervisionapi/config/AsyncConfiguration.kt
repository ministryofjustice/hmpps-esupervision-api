package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
class AsyncConfiguration {
  /** Backs [uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityDataProvider]
   *  implementations that wrap blocking client calls in a [java.util.concurrent.CompletableFuture]. */
  @Bean
  fun eligibilityDataFetchExecutor(): ExecutorService = Executors.newFixedThreadPool(20)
}
