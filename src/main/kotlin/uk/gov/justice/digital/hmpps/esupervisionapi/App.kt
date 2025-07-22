package uk.gov.justice.digital.hmpps.esupervisionapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@SpringBootApplication
@EnableScheduling
class EsupervisionApp {
  @Bean fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
  runApplication<EsupervisionApp>(*args)
}
