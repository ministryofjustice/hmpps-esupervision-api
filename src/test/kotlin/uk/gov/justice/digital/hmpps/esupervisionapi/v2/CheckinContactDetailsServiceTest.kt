package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import java.time.LocalDate

class CheckinContactDetailsServiceTest {
  private val client: INdiliusApiClient = mock()
  private val reconciliation: PractitionerReconciliationService = mock()
  private val service = CheckinContactDetailsService(client, reconciliation)
  private val offender = offenderTemplate.toEntity()
  private val details = ContactDetails(crn = offender.crn, name = Name("Case", "Person"), dateOfBirth = LocalDate.of(1980, 1, 1))

  @Test
  fun `fetch reconciles the returned details`() {
    whenever(client.getContactDetails(offender.crn)).thenReturn(details)

    assertSame(details, service.getContactDetails(offender))

    verify(client).getContactDetails(offender.crn)
    verify(reconciliation).reconcile(offender, details)
  }

  @Test
  fun `strict fetch reconciles the returned details`() {
    whenever(client.getContactDetailsStrict(offender.crn)).thenReturn(details)

    assertSame(details, service.getContactDetailsStrict(offender))

    verify(client).getContactDetailsStrict(offender.crn)
    verify(reconciliation).reconcile(offender, details)
  }

  @Test
  fun `strict fetch preserves failures`() {
    whenever(client.getContactDetailsStrict(offender.crn)).thenThrow(IllegalStateException("Delius unavailable"))

    assertThrows(IllegalStateException::class.java) { service.getContactDetailsStrict(offender) }
  }
}
