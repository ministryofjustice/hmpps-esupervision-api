package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinMode
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class OffenderRepositoryTest : IntegrationTestBase() {

  @Autowired lateinit var offenderRepository: OffenderRepository

  @Autowired lateinit var checkinV2Repository: OffenderCheckinRepository

  @Autowired
  private lateinit var genericNotificationRepository: GenericNotificationRepository

  @Autowired
  private lateinit var clock: Clock

  @AfterEach
  fun cleanUp() {
    genericNotificationRepository.deleteAll()
    checkinV2Repository.deleteAll()
    offenderRepository.deleteAll()
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

    // Offender 5: Ad-hoc, scheduled for today
    val offender5 = createOffenderV2(
      crn = "V200005",
      firstCheckin = today,
      checkinInterval = null,
      mode = CheckinMode.AD_HOC,
    )

    // Offender 6: Ad-hoc, NOT scheduled for today (first checkin 2 days from now)
    val offender6 = createOffenderV2(
      crn = "V200006",
      firstCheckin = today.plusDays(2),
      checkinInterval = null,
      mode = CheckinMode.AD_HOC,
    )

    offenderRepository.saveAll(listOf(offender1, offender2, offender3, offender4, offender5, offender6))

    val result = offenderRepository.findEligibleForCheckinCreation(today, today.plusDays(1))

    // we want only offender 1 and 2
    assertEquals(3, result.size) { "Should only find offenders 1 and 2, and 5, but found: ${result.map { it.crn }}" }
    val crns = result.map { it.crn }.toSet()
    assert(crns.contains("V200001"))
    assert(crns.contains("V200002"))
    assert(crns.contains("V200005"))

    // ensure we skip offenders who already have a checkin in the DB
    val checkin = OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = offender1,
      status = CheckinStatus.CREATED,
      dueDate = today,
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(checkin)

    val resultNoOffender1 = offenderRepository.findEligibleForCheckinCreation(today, today.plusDays(1))
    assertEquals(2, resultNoOffender1.size)
    assertEquals(setOf(offender2.crn, offender5.crn), resultNoOffender1.map { it.crn }.toSet())
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
    offenderRepository.save(targetOffender)

    val targetCheckin = OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = targetOffender,
      status = CheckinStatus.CREATED,
      dueDate = checkinDueDate,
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(targetCheckin)

    // It is day 3, check in will expire tonight but PoP has submitted (should not match)
    val submittedOffender = createOffenderV2("V200002", today, Duration.ofDays(7))
    offenderRepository.save(submittedOffender)

    val submittedCheckin = OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = submittedOffender,
      status = CheckinStatus.SUBMITTED,
      dueDate = checkinDueDate,
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(submittedCheckin)

    // It is day 2, not day 3 and PoP has not submitted (should not match)
    val tomorrowOffender = createOffenderV2("V200003", today.plusDays(1), Duration.ofDays(7))
    offenderRepository.save(tomorrowOffender)

    val tomorrowCheckin = OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = tomorrowOffender,
      status = CheckinStatus.CREATED,
      dueDate = today.plusDays(1),
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(tomorrowCheckin)

    // It is day 3 pop is eligible, BUT notification already sent today (should not match)
    val notifiedOffender = createOffenderV2("V200004", today, Duration.ofDays(7))
    offenderRepository.save(notifiedOffender)

    val notifiedCheckin = OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = notifiedOffender,
      status = CheckinStatus.CREATED,
      dueDate = checkinDueDate,
      createdAt = Instant.now(),
      createdBy = "SYSTEM",
    )
    checkinV2Repository.save(notifiedCheckin)

    val notification = uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotification(
      notificationId = UUID.randomUUID(),
      offenderId = notifiedOffender.id,
      eventType = NotificationType.OffenderCheckinReminder.name,
      createdAt = Instant.now(),
      reference = "REF123",
      templateId = "TEMPLATE_ID",
      recipientType = "OFFENDER",
      channel = "EMAIL",
    )
    genericNotificationRepository.save(notification)

    val results = checkinV2Repository.findEligibleForReminder(
      checkinStartDate = checkinDueDate,
      notificationType = NotificationType.OffenderCheckinReminder.name,
      checkinWindowStart = checkinWindowStart,
    ).toList()

    assertEquals(1, results.size)
    assertEquals(targetCheckin.uuid, results[0].uuid)
    assertEquals("V200001", results[0].offender.crn)
  }

  @Test
  fun `saving SCHEDULED offender with null checkinInterval fails constraint`() {
    val offender = createOffenderV2(
      crn = "V200010",
      firstCheckin = LocalDate.now(),
      checkinInterval = null,
      mode = CheckinMode.SCHEDULED,
    )
    assertThrows<DataIntegrityViolationException> {
      offenderRepository.saveAndFlush(offender)
    }
  }

  @Test
  fun `saving AD_HOC offender with non-null checkinInterval fails constraint`() {
    val offender = createOffenderV2(
      crn = "V200011",
      firstCheckin = LocalDate.now(),
      checkinInterval = Duration.ofDays(7),
      mode = CheckinMode.AD_HOC,
    )
    assertThrows<DataIntegrityViolationException> {
      offenderRepository.saveAndFlush(offender)
    }
  }

  @Test
  fun `saving AD_HOC offender with null checkinInterval succeeds`() {
    val offender = createOffenderV2(
      crn = "V200012",
      firstCheckin = LocalDate.now(),
      checkinInterval = null,
      mode = CheckinMode.AD_HOC,
    )
    val saved = offenderRepository.saveAndFlush(offender)
    assertEquals(CheckinMode.AD_HOC, saved.mode)
    assertEquals(null, saved.checkinInterval)
  }

  private fun createOffenderV2(
    crn: String,
    firstCheckin: LocalDate,
    checkinInterval: Duration? = Duration.ofDays(7),
    status: OffenderStatus = OffenderStatus.VERIFIED,
    mode: CheckinMode = CheckinMode.SCHEDULED,
  ): Offender = Offender(
    uuid = UUID.randomUUID(),
    crn = crn,
    practitionerId = "PRACT1",
    status = status,
    firstCheckin = firstCheckin,
    checkinInterval = checkinInterval,
    mode = mode,
    createdAt = Instant.now(),
    createdBy = "SYSTEM",
    updatedAt = Instant.now(),
    contactPreference = ContactPreference.PHONE,
  )
}
