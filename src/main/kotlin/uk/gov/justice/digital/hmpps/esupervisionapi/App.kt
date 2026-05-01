package uk.gov.justice.digital.hmpps.esupervisionapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import uk.gov.justice.digital.hmpps.esupervisionapi.config.SurveyValueExpansionsConfig
import java.time.Clock
import java.time.ZoneId

private val defaultTimeZone = ZoneId.of(System.getenv("TZ") ?: "Europe/London")

@EnableCaching
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SurveyValueExpansionsConfig::class)
class EsupervisionApp {
  @Bean fun clock(): Clock = Clock.system(defaultTimeZone)
}

fun main(args: Array<String>) {
  runApplication<EsupervisionApp>(*args)
}
