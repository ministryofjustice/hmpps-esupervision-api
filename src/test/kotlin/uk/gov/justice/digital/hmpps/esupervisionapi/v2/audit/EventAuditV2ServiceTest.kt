package uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class EventAuditV2ServiceTest {

  private val clock = Clock.fixed(Instant.parse("2025-12-10T09:00:00Z"), ZoneId.of("UTC"))
  private val auditRepository: EventAuditV2Repository = mock {
    on { save(any<EventAuditV2>()) } doAnswer { it.arguments[0] as EventAuditV2 }
  }
  private val transactionTemplate: TransactionTemplate = mock {
    on { execute<Any?>(any()) } doAnswer {
      (it.getArgument(0) as TransactionCallback<Any?>).doInTransaction(mock())
    }
  }

  private val service = EventAuditV2Service(auditRepository, clock, transactionTemplate)

  private val offender = OffenderV2(
    uuid = UUID.randomUUID(),
    crn = "X123456",
    practitionerId = "PRACT001",
    status = OffenderStatus.INACTIVE,
    firstCheckin = LocalDate.now(clock),
    checkinInterval = CheckinInterval.WEEKLY.duration,
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
    updatedAt = clock.instant(),
    contactPreference = ContactPreference.EMAIL,
  )

  @Test
  fun `records the offender event when practitioner details are present`() {
    val details = ContactDetails(crn = offender.crn, name = Name("John", "Doe"), practitioner = PractitionerDetails(Name("P", "Q")))

    service.recordOffenderEvent(OffenderAuditEventType.OFFENDER_DEACTIVATED, offender, details, "reason")

    verify(auditRepository).save(any())
  }

  @Test
  fun `records the offender event even when practitioner details are missing`() {
    // e.g. an automated deactivation of a POP in reset whose NDelius record has no practitioner
    val details = ContactDetails(crn = offender.crn, name = Name("John", "Doe"), practitioner = null)

    service.recordOffenderEvent(OffenderAuditEventType.OFFENDER_DEACTIVATED, offender, details, "no active events")

    verify(auditRepository).save(any())
  }

  @Test
  fun `records when contact details are entirely absent`() {
    service.recordOffenderEvent(OffenderAuditEventType.OFFENDER_DEACTIVATED, offender, null, "reason")

    verify(auditRepository).save(any())
  }
}
