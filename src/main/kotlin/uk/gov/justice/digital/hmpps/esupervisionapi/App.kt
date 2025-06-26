package uk.gov.justice.digital.hmpps.esupervisionapi

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerService
import java.util.UUID

@SpringBootApplication
class EsupervisionApp

/**
 * This is a DEV-only component. Populates the DB.
 */
@Component
class StartupRunner(val practitionerService: PractitionerService) : ApplicationRunner {
  override fun run(args: ApplicationArguments?) {
    try {
      practitionerService.createPractitioner(
        Practitioner(
          uuid = UUID.randomUUID().toString(),
          firstName = "John",
          lastName = "Doe",
          email = "john@example.bar",
          phoneNumber = null,
          roles = listOf("ROLE_PRACTITIONER", "ROLE_OTHER"),
        ),
      )
    } catch (e: Exception) {
      println("Error creating initial practitioner: ${e.message}")
    }
  }
}

fun main(args: Array<String>) {
  runApplication<EsupervisionApp>(*args)
}
