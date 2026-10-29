package uk.gov.justice.digital.hmpps.esupervisionapi.jobs.batch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ConfigurableApplicationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.CurrentEventBackfillJob
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.OffenderEligibilitySyncJob

class BatchManagerTest {

  private val offenderEligibilitySyncJob: OffenderEligibilitySyncJob = mock()
  private val currentEventBackfillJob: CurrentEventBackfillJob = mock()
  private val context: ConfigurableApplicationContext = mock()

  private fun batchManager(batchType: String) = BatchManager(offenderEligibilitySyncJob, currentEventBackfillJob, context, batchType)

  @Test
  fun `dispatches to the job matching BATCH_TYPE and exits cleanly`() {
    val exitCode = batchManager("OFFENDER_ELIGIBILITY_SYNC").dispatch()

    verify(offenderEligibilitySyncJob).process()
    assertThat(exitCode).isEqualTo(0)
  }

  @Test
  fun `dispatches current event backfill batch`() {
    val exitCode = batchManager("CURRENT_EVENT_BACKFILL").dispatch()

    verify(currentEventBackfillJob).process()
    assertThat(exitCode).isEqualTo(0)
  }

  @Test
  fun `returns a non-zero exit code when the job throws`() {
    whenever(offenderEligibilitySyncJob.process()).thenThrow(RuntimeException("boom"))

    val exitCode = batchManager("OFFENDER_ELIGIBILITY_SYNC").dispatch()

    assertThat(exitCode).isEqualTo(1)
  }

  @Test
  fun `throws when BATCH_TYPE does not match a known BatchType`() {
    val manager = batchManager("NOT_A_REAL_TYPE")

    org.junit.jupiter.api.assertThrows<IllegalArgumentException> { manager.dispatch() }
  }
}
