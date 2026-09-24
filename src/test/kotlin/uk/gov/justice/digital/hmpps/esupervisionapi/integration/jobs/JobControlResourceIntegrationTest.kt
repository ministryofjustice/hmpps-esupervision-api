package uk.gov.justice.digital.hmpps.esupervisionapi.integration.jobs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.CheckinCreationJob
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@TestPropertySource(properties = ["app.jobs.manual-trigger.enabled=true"])
class JobControlResourceIntegrationTest : IntegrationTestBase() {

  @MockitoBean
  private lateinit var checkinCreationJob: CheckinCreationJob

  private val started = CountDownLatch(1)
  private val contextSeenByJob = AtomicReference<RequestAttributes?>()
  private val threadSeenByJob = AtomicReference<String>()

  private fun recordContextOnRun() {
    whenever(checkinCreationJob.process()).then {
      contextSeenByJob.set(RequestContextHolder.getRequestAttributes())
      threadSeenByJob.set(Thread.currentThread().name)
      started.countDown()
      null
    }
  }

  private fun trigger(jobName: String) = webTestClient.post()
    .uri("/v2/jobs/$jobName/run")
    .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
    .exchange()

  /**
   * The reason this endpoint exists rather than a shorter inline call. An upstream call picks up its
   * credentials from whether a servlet request is in scope on the subscribing thread, so a job run
   * inline under the triggering request would authenticate by a route the scheduler never takes -
   * and an auth fault could pass here while the real 9am run still 401s.
   */
  @Test
  fun `the job runs with no request context, the way the scheduler runs it`() {
    recordContextOnRun()

    trigger("checkin-creation").expectStatus().isAccepted

    assertTrue(started.await(5, TimeUnit.SECONDS), "job did not start")
    assertNull(contextSeenByJob.get(), "job ran with a request bound - it would authenticate unlike the scheduled run")
    assertNotNull(threadSeenByJob.get())
    assertEquals("manual-job", threadSeenByJob.get())
  }

  @Test
  fun `an unknown job name is rejected rather than silently doing nothing`() {
    trigger("not-a-job").expectStatus().isNotFound
  }

  @Test
  fun `the listing reports what can be triggered here`() {
    webTestClient.get()
      .uri("/v2/jobs")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.jobs").value<List<String>> { assertTrue(it.contains("checkin-creation"), "got $it") }
  }

  @Test
  fun `triggering requires a token`() {
    webTestClient.post().uri("/v2/jobs/checkin-creation/run").exchange().expectStatus().isUnauthorized
  }

  @Test
  fun `triggering requires the role`() {
    webTestClient.post()
      .uri("/v2/jobs/checkin-creation/run")
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }
}
