package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerReconciliationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications.NotificationOrchestratorService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.stream.Stream

class CustomQuestionsReminderJobTest {
  @Test
  fun `a reconciliation failure does not prevent reminders`() {
    val clock = Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC)
    val failing = offenderTemplate.copy(crn = "X000001", firstCheckin = LocalDate.now(clock).plusDays(4)).toEntity()
    val succeeding = offenderTemplate.copy(crn = "X000002", firstCheckin = LocalDate.now(clock).plusDays(4)).toEntity()
    ReflectionTestUtils.setField(failing, "id", 1L)
    ReflectionTestUtils.setField(succeeding, "id", 2L)
    val details = listOf(failing, succeeding).map {
      ContactDetails(crn = it.crn, name = Name("Case", "Person"), dateOfBirth = LocalDate.of(1980, 1, 1))
    }
    val repository: OffenderRepository = mock()
    whenever(repository.findEligibleForPractitionerCustomQuestionsReminder(any(), any(), any()))
      .thenAnswer { Stream.of(failing, succeeding) }
    val client: INdiliusApiClient = mock()
    whenever(client.getContactDetailsForMultiple(any())).thenReturn(details)
    val reconciliation: PractitionerReconciliationService = mock()
    doThrow(DataAccessResourceFailureException("database failure")).whenever(reconciliation).reconcile(eq(failing.id), any(), eq(details[0]))
    whenever(reconciliation.reconcile(eq(succeeding.id), any(), eq(details[1]))).thenReturn("NEW")
    val notifications: NotificationOrchestratorService = mock()
    val logs: JobLogRepository = mock {
      on { saveAndFlush(any<JobLog>()) } doAnswer { it.getArgument<JobLog>(0) }
    }
    val transactions: TransactionTemplate = mock {
      on { execute<Any?>(any()) } doAnswer {
        (it.getArgument(0) as TransactionCallback<Any?>).doInTransaction(mock())
      }
    }

    CustomQuestionsReminderJob(clock, repository, client, notifications, logs, transactions, mock(), reconciliation).process()

    verify(notifications).sendPractitionerCustomQuestionsReminder(argThat { contactDetails.crn == succeeding.crn && practitionerId == "NEW" })
    verify(notifications).sendPractitionerCustomQuestionsReminder(argThat { contactDetails.crn == failing.crn && practitionerId == failing.practitionerId })
  }
}
