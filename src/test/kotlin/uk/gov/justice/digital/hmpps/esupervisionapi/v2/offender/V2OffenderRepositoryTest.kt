package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class V2OffenderRepositoryTest : IntegrationTestBase() {

  @Autowired lateinit var offenderV2Repository: OffenderV2Repository

  @Autowired lateinit var checkinV2Repository: OffenderCheckinV2Repository

  @AfterEach
  fun cleanUp() {
    offenderV2Repository.deleteAll()
  }

  @Test
  @Transactional
  fun `findEligibleForCheckinCreation - should filter by schedule`() {
    val today = LocalDate.of(2026, 1, 21)
    val now = Instant.now()

    // Offender 1: Scheduled for today (first checkin is today)
    val offender1 = createOffenderV2(
      crn = "V200001",
      firstCheckin = today,
      checkinInterval = Duration.ofDays(7),
    )

    // Offender 2: Scheduled for today (first checkin was 7 days ago)
    val offender2 = createOffenderV2(
      crn = "V200002",
      firstCheckin = today.minusDays(7),
      checkinInterval = Duration.ofDays(7),
    )

    // Offender 3: NOT scheduled for today (first checkin was 6 days ago)
    val offender3 = createOffenderV2(
      crn = "V200003",
      firstCheckin = today.minusDays(6),
      checkinInterval = Duration.ofDays(7),
    )

    // Offender 4: NOT scheduled for today (first checkin is tomorrow)
    val offender4 = createOffenderV2(
      crn = "V200004",
      firstCheckin = today.plusDays(1),
      checkinInterval = Duration.ofDays(7),
    )

    offenderV2Repository.saveAll(listOf(offender1, offender2, offender3, offender4))

    val result = offenderV2Repository.findEligibleForCheckinCreation(today, today.plusDays(1)).toList()

    // we want only offender 1 and 2
    assertEquals(2, result.size) { "Should only find offenders 1 and 2, but found: ${result.map { it.crn }}" }
    val crns = result.map { it.crn }.toSet()
    assert(crns.contains("V200001"))
    assert(crns.contains("V200002"))

    // ensure we skip offenders who already have a checkin in the DB
    val checkin = uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2(
      uuid = UUID.randomUUID(),
      offender = offender1,
      status = uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status.CREATED,
      dueDate = today,
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(checkin)

    val resultNoOffender1 = offenderV2Repository.findEligibleForCheckinCreation(today, today.plusDays(1)).toList()
    assertEquals(1, resultNoOffender1.size)
    assertEquals("V200002", resultNoOffender1.first().crn)
  }

  @Test
  @Transactional
  fun `findEligibleForReminder - returns only CREATED checkins due today`() {
    val today = LocalDate.now()
    val tomorrow = today.plusDays(1)

    // Due today and has not submitted (should match)
    val targetOffender = createOffenderV2("V200001", today, Duration.ofDays(7))
    offenderV2Repository.save(targetOffender)

    val targetCheckin = uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2(
      uuid = UUID.randomUUID(),
      offender = targetOffender,
      status = uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status.CREATED,
      dueDate = today,
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(targetCheckin)

    // Due today but PoP has submitted (should not match)
    val submittedOffender = createOffenderV2("V200002", today, Duration.ofDays(7))
    offenderV2Repository.save(submittedOffender)

    val submittedCheckin = uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2(
      uuid = UUID.randomUUID(),
      offender = submittedOffender,
      status = uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status.SUBMITTED,
      dueDate = today,
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(submittedCheckin)

    // Due tomorrow and has not submitted (should not match)
    val tomorrowOffender = createOffenderV2("V200003", tomorrow, Duration.ofDays(7))
    offenderV2Repository.save(tomorrowOffender)

    val tomorrowCheckin = uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2(
      uuid = UUID.randomUUID(),
      offender = tomorrowOffender,
      status = uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status.CREATED,
      dueDate = tomorrow,
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(tomorrowCheckin)

    val results = checkinV2Repository.findEligibleForReminder(today).toList()

    assertEquals(1, results.size)
    assertEquals(targetCheckin.uuid, results[0].uuid)
    assertEquals("V200001", results[0].offender.crn)
  }

  private fun createOffenderV2(
    crn: String,
    firstCheckin: LocalDate,
    checkinInterval: Duration,
    status: OffenderStatus = OffenderStatus.VERIFIED,
  ): OffenderV2 = OffenderV2(
    uuid = UUID.randomUUID(),
    crn = crn,
    practitionerId = "PRACT1",
    status = status,
    firstCheckin = firstCheckin,
    checkinInterval = checkinInterval,
    createdAt = Instant.now(),
    createdBy = "SYSTEM",
    updatedAt = Instant.now(),
    contactPreference = ContactPreference.PHONE,
  )
}
