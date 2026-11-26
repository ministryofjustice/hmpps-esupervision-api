package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional

class PractitionerReconciliationServiceTest {
  private val now = Instant.parse("2026-11-26T12:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val repository: OffenderRepository = mock()
  private val transactions: TransactionTemplate = mock {
    on { execute<Any?>(any()) } doAnswer {
      (it.getArgument(0) as TransactionCallback<Any?>).doInTransaction(mock())
    }
  }
  private val entityManager: EntityManager = mock()
  private val service = PractitionerReconciliationService(repository, transactions, clock, entityManager)
  private val offender = offenderTemplate.copy(practitionerId = "OLD").toEntity().also {
    ReflectionTestUtils.setField(it, "id", 1L)
    it.updatedAt = now.minusSeconds(100)
  }

  private fun details(username: String? = "NEW", unallocated: Boolean? = false) = ContactDetails(
    crn = offender.crn,
    name = Name("Case", "Person"),
    dateOfBirth = LocalDate.of(1980, 1, 1),
    practitioner = PractitionerDetails(name = Name("New", "Practitioner"), username = username, unallocated = unallocated),
  )

  @Test
  fun `updates the locked record and the detached offender`() {
    val persisted = offenderTemplate.copy(practitionerId = "OLD").toEntity().also {
      ReflectionTestUtils.setField(it, "id", 1L)
      it.updatedAt = offender.updatedAt
    }
    whenever(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(persisted))

    service.reconcile(offender, details())

    assertEquals("NEW", persisted.practitionerId)
    assertEquals(now, persisted.updatedAt)
    assertEquals("NEW", offender.practitionerId)
    assertEquals(now, offender.updatedAt)
  }

  @Test
  fun `unchanged allocation preserves id and timestamp after checking the locked row`() {
    val previousTimestamp = offender.updatedAt
    whenever(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(offender))

    service.reconcile(offender, details(username = "old"))

    assertEquals("OLD", offender.practitionerId)
    assertEquals(previousTimestamp, offender.updatedAt)
    verify(repository).findByIdForUpdate(1L)
  }

  @Test
  fun `unavailable allocations do not lock or change timestamp`() {
    val previousTimestamp = offender.updatedAt
    service.reconcile(offender, details(username = " "))
    service.reconcile(offender, details(username = null))
    service.reconcile(offender, details(unallocated = true))
    service.reconcile(offender, details().copy(practitioner = null))
    service.reconcile(offender, null)

    assertEquals("OLD", offender.practitionerId)
    assertEquals(previousTimestamp, offender.updatedAt)
    verify(repository, never()).findByIdForUpdate(any())
  }

  @Test
  fun `matching stale offender snapshot does not prevent updating the locked record`() {
    val persisted = offenderTemplate.copy(practitionerId = "NEW").toEntity().also {
      it.updatedAt = now.minusSeconds(20)
    }
    whenever(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(persisted))

    service.reconcile(offender, details(username = "OLD"))

    assertEquals("OLD", persisted.practitionerId)
    assertEquals(now, persisted.updatedAt)
    assertEquals("OLD", offender.practitionerId)
    assertEquals(now, offender.updatedAt)
  }

  @Test
  fun `matching stale batch snapshot does not prevent updating the locked record`() {
    val persisted = offenderTemplate.copy(practitionerId = "NEW").toEntity().also {
      it.updatedAt = now.minusSeconds(20)
    }
    whenever(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(persisted))

    assertEquals("OLD", service.reconcile(1L, "OLD", details(username = "OLD")))
    assertEquals("OLD", persisted.practitionerId)
    assertEquals(now, persisted.updatedAt)
  }

  @Test
  fun `an allocation already updated by another journey keeps its timestamp`() {
    val persisted = offenderTemplate.copy(practitionerId = "NEW").toEntity().also {
      it.updatedAt = now.minusSeconds(20)
    }
    whenever(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(persisted))

    service.reconcile(offender, details())

    assertEquals("NEW", offender.practitionerId)
    assertEquals(persisted.updatedAt, offender.updatedAt)
    assertEquals(now.minusSeconds(20), persisted.updatedAt)
  }

  @Test
  fun `rejects contact details for a different case`() {
    assertThrows(IllegalArgumentException::class.java) {
      service.reconcile(offender, details().copy(crn = "X999999"))
    }
    verify(repository, never()).findByIdForUpdate(any())
  }

  @Test
  fun `batch projection reconciliation returns the allocated username`() {
    whenever(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(offender))

    assertEquals("NEW", service.reconcile(1L, "OLD", details()))
    assertEquals("NEW", offender.practitionerId)
    assertEquals("OLD", service.reconcile(1L, "OLD", details(unallocated = true)))
  }

  @Test
  fun `transaction failures are translated for batch fallback`() {
    val cause = CannotCreateTransactionException("database unavailable")
    whenever(transactions.execute<Any?>(any())).thenThrow(cause)

    val error = assertThrows(DataAccessResourceFailureException::class.java) {
      service.reconcile(1L, "OLD", details())
    }
    assertSame(cause, error.cause)
  }

  @Test
  fun `JPA refresh failures are translated for batch fallback`() {
    val cause = PersistenceException("refresh failed")
    whenever(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(offender))
    doThrow(cause).whenever(entityManager).refresh(offender)

    val error = assertThrows(DataAccessResourceFailureException::class.java) {
      service.reconcile(offender, details())
    }
    assertSame(cause, error.cause)
  }

  @Test
  fun `batch rejects contact details for a different case`() {
    whenever(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(offender))

    assertThrows(IllegalArgumentException::class.java) {
      service.reconcile(1L, "OLD", details().copy(crn = "X999999"))
    }
  }
}
