package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Status
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class V2OffenderRepositoryTest : IntegrationTestBase() {

  @Autowired lateinit var offenderV2Repository: OffenderV2Repository

  @Autowired lateinit var checkinV2Repository: OffenderCheckinV2Repository

  @Autowired
  private lateinit var genericNotificationV2Repository: GenericNotificationV2Repository

  @Autowired
  private lateinit var clock: Clock

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
    val today = clock.today()
    // the check in due date was 2 days ago, meaning today is day 3 and also the day it will expire at 23:59
    val checkinDueDate = today.minusDays(2)
    val checkinWindowStart = checkinDueDate.atStartOfDay(clock.zone).toInstant()

    // It is day 3, check in will expire tonight and PoP has not submitted (should match)
    val targetOffender = createOffenderV2("V200001", today, Duration.ofDays(7))
    offenderV2Repository.save(targetOffender)

    val targetCheckin = OffenderCheckinV2(
      uuid = UUID.randomUUID(),
      offender = targetOffender,
      status = CheckinV2Status.CREATED,
      dueDate = checkinDueDate,
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(targetCheckin)

    // It is day 3, check in will expire tonight but PoP has submitted (should not match)
    val submittedOffender = createOffenderV2("V200002", today, Duration.ofDays(7))
    offenderV2Repository.save(submittedOffender)

    val submittedCheckin = OffenderCheckinV2(
      uuid = UUID.randomUUID(),
      offender = submittedOffender,
      status = CheckinV2Status.SUBMITTED,
      dueDate = checkinDueDate,
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(submittedCheckin)

    // It is day 2, not day 3 and PoP has not submitted (should not match)
    val tomorrowOffender = createOffenderV2("V200003", today.plusDays(1), Duration.ofDays(7))
    offenderV2Repository.save(tomorrowOffender)

    val tomorrowCheckin = OffenderCheckinV2(
      uuid = UUID.randomUUID(),
      offender = tomorrowOffender,
      status = CheckinV2Status.CREATED,
      dueDate = today.plusDays(1),
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(tomorrowCheckin)

    // It is day 3 pop is eligible, BUT notification already sent today (should not match)
    val notifiedOffender = createOffenderV2("V200004", today, Duration.ofDays(7))
    offenderV2Repository.save(notifiedOffender)

    val notifiedCheckin = OffenderCheckinV2(
      uuid = UUID.randomUUID(),
      offender = notifiedOffender,
      status = CheckinV2Status.CREATED,
      dueDate = checkinDueDate,
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(notifiedCheckin)

    val notification = uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationV2(
      notificationId = UUID.randomUUID(),
      offender = notifiedOffender,
      eventType = NotificationType.OffenderCheckinReminder.name,
      createdAt = Instant.now(),
      reference = "REF123",
      templateId = "TEMPLATE_ID",
      recipientType = "OFFENDER",
      channel = "EMAIL",
    )
    genericNotificationV2Repository.save(notification)

    val results = checkinV2Repository.findEligibleForReminder(
      checkinStartDate = checkinDueDate,
      notificationType = NotificationType.OffenderCheckinReminder.name,
      checkinWindowStart = checkinWindowStart,
    ).toList()

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
