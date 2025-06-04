package uk.gov.justice.digital.hmpps.esupervisionapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EsupervisionApp

fun main(args: Array<String>) {
  runApplication<EsupervisionApp>(*args)
}
