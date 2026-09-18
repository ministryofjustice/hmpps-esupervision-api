package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Configuration
class AsyncConfiguration {

  /**
   * We need to define this to make sure any custom executor does not interfere with the default.
   */
  @Bean(name = ["taskExecutor", "applicationTaskExecutor"])
  @Primary
  fun taskExecutor(builder: ThreadPoolTaskExecutorBuilder): ThreadPoolTaskExecutor {
    // retains all application.yml settings
    return builder.build().also {
      LOGGER.info("Created task executor: core pool size=${it.corePoolSize}, max pool size=${it.maxPoolSize}, queue capacity=${it.queueCapacity}")
    }
  }

  /** Backs [uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityDataProvider]
   *  implementations that wrap blocking client calls in a [java.util.concurrent.CompletableFuture]. */
  @Bean(name = ["eligibilityDataFetchExecutor"])
  fun eligibilityDataFetchExecutor(): Executor = Executors.newFixedThreadPool(20)

  companion object {
    val LOGGER = logger<AsyncConfiguration>()
  }
}
