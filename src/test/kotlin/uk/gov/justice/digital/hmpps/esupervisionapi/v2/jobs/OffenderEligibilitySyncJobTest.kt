package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class OffenderEligibilitySyncJobTest {

  private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))
  private val jobLogRepository: JobLogRepository = mock {
    on { saveAndFlush(any<JobLog>()) } doAnswer { it.arguments[0] as JobLog }
  }

  private val job = OffenderEligibilitySyncJob(clock, jobLogRepository)

  @Test
  fun `process records a job log with the expected type and end time`() {
    job.process()

    val captor = argumentCaptor<JobLog>()
    verify(jobLogRepository, times(2)).saveAndFlush(captor.capture())
    val logEntry = captor.firstValue
    assertThat(logEntry.jobType).isEqualTo(OffenderEligibilitySyncJob.JOB_TYPE)
    assertThat(logEntry.createdAt).isEqualTo(clock.instant())
    assertThat(logEntry.endedAt).isEqualTo(clock.instant())
  }
}
