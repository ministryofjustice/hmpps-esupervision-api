package uk.gov.justice.digital.hmpps.esupervisionapi.integration.stats

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.PRACTITIONER_ALICE
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.PRACTITIONER_BOB
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.PRACTITIONER_DAVE
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.create
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.InMemoryPractitionerSiteRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.stats.StatsService
import java.time.LocalDate

class StatsServiceTest : IntegrationTestBase() {
  @AfterEach
  fun tearDown() {
    this.offenderRepository.deleteAll()
  }

  @Test
  fun `known practitioner location registrations`() {
    // register 3 PoPs - 2 for practitioner alice, one for bob
    val offender1 = Offender.create("Offender One", "o111111", firstCheckinDate = LocalDate.now(), email = "offender1@example.com", status = OffenderStatus.VERIFIED, practitioner = PRACTITIONER_ALICE)
    val offender2 = Offender.create("Offender Two", "o222222", firstCheckinDate = LocalDate.now(), email = "offender2@example.com", status = OffenderStatus.VERIFIED, practitioner = PRACTITIONER_ALICE)
    val offender3 = Offender.create("Offender Three", "o333333", firstCheckinDate = LocalDate.now(), email = "offender3@example.com", status = OffenderStatus.VERIFIED, practitioner = PRACTITIONER_BOB)

    this.offenderRepository.saveAll(listOf(offender1, offender2, offender3))

    // get stats
    val locationMapping = mapOf(
      PRACTITIONER_ALICE.externalUserId() to "Narnia",
      PRACTITIONER_BOB.externalUserId() to "Utopia",
    )

    val locations = InMemoryPractitionerSiteRepository(locationMapping)
    val statsService = StatsService(this.offenderRepository, locations)

    val stats = statsService.practitionerRegistrations()

    // should be one summary record for each practitioner
    Assertions.assertEquals(2, stats.size)

    val aliceStats = stats.find { it.practitioner == PRACTITIONER_ALICE.externalUserId() }!!
    Assertions.assertEquals(2, aliceStats.registrationCount, "Unexpected count for Alice")
    Assertions.assertEquals("Narnia", aliceStats.siteName, "Unexpected location for Alice")

    val bobStats = stats.find { it.practitioner == PRACTITIONER_BOB.externalUserId() }!!
    Assertions.assertEquals(1, bobStats.registrationCount, "Unexpected count for Bob")
    Assertions.assertEquals("Utopia", bobStats.siteName, "Unexpected location for Bob")
  }

  @Test
  fun `unknown practitioner location registrations`() {
    // register 3 PoPs to different practitioners
    val offender1 = Offender.create("Offender One", "o111111", firstCheckinDate = LocalDate.now(), email = "offender1@example.com", status = OffenderStatus.VERIFIED, practitioner = PRACTITIONER_ALICE)
    val offender2 = Offender.create("Offender Two", "o222222", firstCheckinDate = LocalDate.now(), email = "offender2@example.com", status = OffenderStatus.VERIFIED, practitioner = PRACTITIONER_BOB)
    val offender3 = Offender.create("Offender Three", "o333333", firstCheckinDate = LocalDate.now(), email = "offender3@example.com", status = OffenderStatus.VERIFIED, practitioner = PRACTITIONER_DAVE)

    this.offenderRepository.saveAll(listOf(offender1, offender2, offender3))

    // only location of one practitioner is known
    val locationMapping = mapOf(
      PRACTITIONER_ALICE.externalUserId() to "Burnley",
    )

    val locations = InMemoryPractitionerSiteRepository(locationMapping)
    val statsService = StatsService(this.offenderRepository, locations)

    val stats = statsService.practitionerRegistrations()

    // should be one summary record for each practitioner
    Assertions.assertEquals(3, stats.size, "Unexpected practitioner stats count")

    val aliceStats = stats.find { it.practitioner == PRACTITIONER_ALICE.externalUserId() }!!
    Assertions.assertEquals(1, aliceStats.registrationCount, "Unexpected count for Alice")
    Assertions.assertEquals("Burnley", aliceStats.siteName, "Unexpected location for Alice")

    val bobStats = stats.find { it.practitioner == PRACTITIONER_BOB.externalUserId() }!!
    Assertions.assertEquals(1, bobStats.registrationCount, "Unexpected count for Bob")
    Assertions.assertEquals(StatsService.UNKNOWN_LOCATION_NAME, bobStats.siteName, "Unexpected location for Bob")

    val daveStats = stats.find { it.practitioner == PRACTITIONER_DAVE.externalUserId() }!!
    Assertions.assertEquals(1, daveStats.registrationCount, "Unexpected count for Dave")
    Assertions.assertEquals(StatsService.UNKNOWN_LOCATION_NAME, daveStats.siteName, "Unexpected location for Dave")
  }

  @Test
  fun `incomplete registrations ignored`() {
    // register 3 PoPs with different statuses
    // verified and inactive registrations are counted in the stats, incomplete registrations are ignored
    val offender1 = Offender.create("Offender One", "o111111", firstCheckinDate = LocalDate.now(), email = "offender1@example.com", status = OffenderStatus.INITIAL, practitioner = PRACTITIONER_ALICE)
    val offender2 = Offender.create("Offender Two", "o222222", firstCheckinDate = LocalDate.now(), email = "offender2@example.com", status = OffenderStatus.VERIFIED, practitioner = PRACTITIONER_ALICE)
    val offender3 = Offender.create("Offender Three", "o333333", firstCheckinDate = LocalDate.now(), email = "offender3@example.com", status = OffenderStatus.INACTIVE, practitioner = PRACTITIONER_ALICE)

    this.offenderRepository.saveAll(listOf(offender1, offender2, offender3))

    val locationMapping = mapOf(
      PRACTITIONER_ALICE.externalUserId() to "Cambridge",
    )

    val locations = InMemoryPractitionerSiteRepository(locationMapping)
    val statsService = StatsService(this.offenderRepository, locations)

    val stats = statsService.practitionerRegistrations()

    // only one practitioner
    Assertions.assertEquals(1, stats.size, "Unexpected practitioner stats count")

    val aliceStats = stats.first()
    Assertions.assertEquals(2, aliceStats.registrationCount, "Unexpected count for Alice")
    Assertions.assertEquals("Cambridge", aliceStats.siteName, "Unexpected location for Alice")
  }
}
