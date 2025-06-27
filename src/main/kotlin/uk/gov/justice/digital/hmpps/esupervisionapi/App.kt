package uk.gov.justice.digital.hmpps.esupervisionapi

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerService
import java.util.UUID

@SpringBootApplication
class EsupervisionApp

fun main(args: Array<String>) {
  runApplication<EsupervisionApp>(*args)
}
