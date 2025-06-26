package uk.gov.justice.digital.hmpps.esupervisionapi.integration.offender

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.test.context.ActiveProfiles
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.wiremock.HmppsAuthApiExtension
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetupService
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.OffenderInfo
import java.time.LocalDate
import java.util.UUID

@ExtendWith(HmppsAuthApiExtension::class)
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
class OffenderSetup : IntegrationTestBase() {

  // @Autowired lateinit var entityManager: EntityManager
  @Autowired lateinit var offenderInviteService: OffenderSetupService
  // @Autowired lateinit var practitionerService: PractitionerService

//  @BeforeEach
//   fun setup() {
//     val session = entityManager.unwrap(Session::class.java)
//     val practitioner = Practitioner(
//       uuid = UUID.randomUUID(),
//       firstName = "Charles",
//       lastName = "Practitioner",
//       email = "practicioner@example.com",
//       roles = listOf("PRACTITIONER")
//     )
//     session.persist(practitioner)
//  }

  @Disabled
  @Test
  fun `practitioner wants to add few offenders`() {
    // fail { "this does not work" }
    val result = offenderInviteService.startOffenderSetup(
      OffenderInfo(
        setupUuid = UUID.randomUUID(),
        practitionerId = UUID.randomUUID().toString(),
        firstName = "John",
        lastName = "Smith",
        dateOfBirth = LocalDate.of(1980, 1, 1),
        phoneNumber = "7701023399",
      ),
    )

    assertNotNull(result)
  }
}
