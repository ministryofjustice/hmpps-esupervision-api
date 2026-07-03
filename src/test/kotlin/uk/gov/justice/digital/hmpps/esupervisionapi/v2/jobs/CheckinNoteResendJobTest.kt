package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class CheckinNoteResendJobTest {

  private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))
  private val service: CheckinNoteResendService = mock()
  private val jobLogRepository: JobLogRepository = mock {
    on { saveAndFlush(any<JobLog>()) } doAnswer { it.arguments[0] as JobLog }
  }

  private val job = CheckinNoteResendJob(clock, service, jobLogRepository, batchSize = 50, eventsPerSecond = 2.0)

  @Test
  fun `process delegates to the service and records a job log`() {
    job.process()

    verify(service).processPending(eq(50), eq(2.0))
    val captor = argumentCaptor<JobLog>()
    verify(jobLogRepository, times(2)).saveAndFlush(captor.capture())
    val logEntry = captor.lastValue
    assertThat(logEntry.jobType).isEqualTo("CHECKIN_NOTE_RESEND")
    assertThat(logEntry.endedAt).isEqualTo(clock.instant())
  }

  @Test
  fun `job log is closed even when the service throws`() {
    whenever(service.processPending(any(), any())).thenThrow(RuntimeException("boom"))

    job.process()

    val captor = argumentCaptor<JobLog>()
    verify(jobLogRepository, times(2)).saveAndFlush(captor.capture())
    assertThat(captor.lastValue.endedAt).isEqualTo(clock.instant())
  }
}
