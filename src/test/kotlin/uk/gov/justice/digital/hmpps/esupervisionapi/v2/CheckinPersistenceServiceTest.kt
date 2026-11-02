package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CheckinPersistenceServiceTest {
  private val checkinRepository: OffenderCheckinRepository = mock()
  private val service = CheckinPersistenceService(
    checkinRepository = checkinRepository,
    eventAuditService = mock(),
    appEventPublisher = mock(),
    offenderEventLogRepository = mock(),
    clock = Clock.systemUTC(),
    checkinDeliusEventService = mock(),
  )

  private fun newCheckin() = OffenderCheckin(
    uuid = UUID.randomUUID(),
    offender = offenderTemplate.toEntity(),
    status = CheckinStatus.CREATED,
    dueDate = LocalDate.of(2026, 11, 2),
    createdAt = Instant.parse("2026-11-02T08:00:00Z"),
    createdBy = "SYSTEM",
  )

  @Test
  fun `checkinCreation refuses fields the native insert would drop`() {
    val checkin = newCheckin().apply {
      livenessEnabled = true
      linkStatus = CheckinLinkStatus.FAILED
    }

    val error = assertThrows<IllegalArgumentException> { service.checkinCreation(checkin, mock()) }

    assertTrue(error.message!!.contains("livenessEnabled"))
    assertTrue(error.message!!.contains("linkStatus"))
    verify(checkinRepository, never()).maybeInsert(any())
  }

  @Test
  fun `checkinCreation returns null without finalising when a CREATED check-in already exists`() {
    // maybeInsert is unstubbed, so it returns null as it does on conflict
    val result = service.checkinCreation(newCheckin(), mock())

    assertTrue(result == null)
    verify(checkinRepository).maybeInsert(any())
  }
}
