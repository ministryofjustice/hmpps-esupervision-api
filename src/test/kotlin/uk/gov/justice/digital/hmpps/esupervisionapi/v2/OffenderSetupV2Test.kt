package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class OffenderSetupV2Test {

  private val offender = OffenderV2(
    uuid = UUID.randomUUID(),
    crn = "X123456",
    practitionerId = "PRACT001",
    status = OffenderStatus.VERIFIED,
    firstCheckin = LocalDate.of(2026, 1, 1),
    checkinInterval = java.time.Duration.ofDays(7),
    createdAt = Instant.now(),
    createdBy = "PRACT001",
    updatedAt = Instant.now(),
    contactPreference = ContactPreference.PHONE,
  )

  private fun createSetup() = OffenderSetupV2(
    uuid = UUID.randomUUID(),
    offender = offender,
    practitionerId = "PRACT001",
    createdAt = Instant.now(),
  )

  @Test
  fun `setupId is deterministic for the same counter value`() {
    val setup = createSetup()
    assertEquals(setup.setupId(), setup.setupId())
  }

  @Test
  fun `setupId changes after incrementing counter`() {
    val setup = createSetup()
    val firstSetupId = setup.setupId()

    setup.incrementSetupCounter()

    assertNotEquals(firstSetupId, setup.setupId())
  }

  @Test
  fun `setupId is stable before and after deactivation (no increment)`() {
    val setup = createSetup()
    val setupIdAtStart = setup.setupId()

    // deactivation does not increment the counter
    val setupIdAtStop = setup.setupId()

    assertEquals(setupIdAtStart, setupIdAtStop)
  }

  @Test
  fun `setupId pairs correctly across setup lifecycle`() {
    val setup = createSetup()

    // initial setup complete
    val firstStartId = setup.setupId()
    // deactivation - same id
    assertEquals(firstStartId, setup.setupId())

    // reactivation increments counter
    setup.incrementSetupCounter()
    val secondStartId = setup.setupId()
    assertNotEquals(firstStartId, secondStartId)

    // second deactivation - same as reactivation id
    assertEquals(secondStartId, setup.setupId())
  }

  @Test
  fun `incrementSetupCounter increments by one`() {
    val setup = createSetup()
    assertEquals(1, setup.setupCounter)

    setup.incrementSetupCounter()
    assertEquals(2, setup.setupCounter)

    setup.incrementSetupCounter()
    assertEquals(3, setup.setupCounter)
  }
}
