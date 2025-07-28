package uk.gov.justice.digital.hmpps.esupervisionapi.integration.offender

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.create
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class OffenderRepositoryTest : IntegrationTestBase() {

  @BeforeEach
  fun setupOffenders() {
    val practitionerAlice = Practitioner.create("alice")
    val practitionerBob = Practitioner.create("dave")
    practitionerRepository.saveAll(listOf(practitionerAlice, practitionerBob))

    val now = Instant.now()
    val today = LocalDate.now()

    fun newOffender(name: String, status: OffenderStatus, firstCheckinDate: LocalDate, practitioner: Practitioner = practitionerAlice): Offender = Offender.create(
      name = name,
      status = status,
      firstCheckinDate = firstCheckinDate,
      createdAt = now.minus(Duration.ofDays(20)),
      updatedAt = now.minus(Duration.ofDays(10)),
      practitioner = practitioner,
    )

    val offender1 = newOffender("Offender(active) First", OffenderStatus.VERIFIED, today)
    val offender2 = newOffender("Offender(active) Second", OffenderStatus.VERIFIED, today.plusDays(1))
    val offender3 = newOffender("Offender(inactive) Third", OffenderStatus.INACTIVE, today)
    val offender4 = newOffender("Offender(inactive) Fourth", OffenderStatus.INITIAL, today)

    offenderRepository.saveAll(listOf(offender1, offender2, offender3, offender4))

    val checkinForOffender2 = OffenderCheckin.create(
      offender = offender2,
      createdBy = practitionerAlice,
      dueDate = today.plusDays(1),
    )
    checkinRepository.saveAll(listOf(checkinForOffender2))
  }

  @Test
  @Transactional
  fun `get offenders due for an invite`() {
    val now = LocalDate.now()
    var offenders = offenderRepository.findAllCheckinNotificationCandidates(
      now,
      now.plusDays(1),
    )
      .toList()

    // NOTE: we should get two records: 2 offenders have the right status,
    // and our query *does not* filter out records that already have
    // checkins  scheduled outside the specified bounds.
    // We could move the calculations to the query but
    // at the moment this would make testing (with H2 DB) impossible
    Assertions.assertEquals(2, offenders.size)

    offenders = offenderRepository.findAllCheckinNotificationCandidates(
      now.plusDays(1),
      now.plusDays(2),
    )
      .toList()

    // NOTE: this time we expect one record as a checkin  is already
    // in place for the specified time bounds
    Assertions.assertEquals(1, offenders.size)
  }
}
