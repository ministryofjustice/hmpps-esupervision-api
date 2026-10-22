package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.same
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.OffenderAuditEventType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityCheckOutcome
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityChecker
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityEvaluationEngine
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.eligibilityData
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class OffenderEligibilitySyncJobTest {

  private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))
  private val jobLogRepository: JobLogRepository = mock {
    on { saveAndFlush(any<JobLog>()) } doAnswer { it.arguments[0] as JobLog }
  }
  private val offenderRepository: OffenderRepository = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val eligibilityChecker: EligibilityChecker = mock()
  private val deactivationService: OffenderDeactivationService = mock()

  private val job = OffenderEligibilitySyncJob(
    clock = clock,
    jobLogRepository = jobLogRepository,
    offenderRepository = offenderRepository,
    ndiliusApiClient = ndiliusApiClient,
    eligibilityChecker = eligibilityChecker,
    offenderDeactivationService = deactivationService,
    chunkSize = 100,
  )

  @Test
  fun `process records a job log with the expected type and end time`() {
    whenever(
      offenderRepository.findAllByStatusAndIdGreaterThanOrderByIdAsc(
        eq(OffenderStatus.VERIFIED),
        any(),
        any(),
      ),
    ).thenReturn(emptyList())

    job.process()

    val captor = argumentCaptor<JobLog>()
    verify(jobLogRepository, times(2)).saveAndFlush(captor.capture())
    val logEntry = captor.firstValue
    assertThat(logEntry.jobType).isEqualTo(OffenderEligibilitySyncJob.JOB_TYPE)
    assertThat(logEntry.createdAt).isEqualTo(clock.instant())
    assertThat(logEntry.endedAt).isEqualTo(clock.instant())
  }

  @Test
  fun `ineligible offender is deactivated with the engine message and prefilled eligibility data`() {
    val offender = offender("X000001", id = 1)
    val details = contactDetails(offender.crn)
    givenPage(listOf(offender), listOf(details))
    val reason = "John Doe is not eligible for online check ins because they have been recalled."
    whenever(eligibilityChecker.check(same(offender), eq(details.name), any()))
      .thenReturn(EligibilityResult(EligibilityCheckOutcome.INELIGIBLE, reason, "IS_RECALLED", "ESPRC"))

    job.process()

    verify(ndiliusApiClient).getContactDetailsForMultiple(listOf(offender.crn))
    val cacheCaptor = argumentCaptor<EligibilityEvaluationEngine.Cache>()
    verify(eligibilityChecker).check(same(offender), eq(details.name), cacheCaptor.capture())
    val cache = cacheCaptor.firstValue
    assertThat(cache.getValue("NDELIUS").get()).isEqualTo(details.eligibilityData())
    assertThat(cache.getValue("MANUAL").get()).isEqualTo(
      mapOf(
        "DEVICE_RESTRICTION" to false,
        "ACCREDITED_PROGRAMME" to true,
        "YOUTH_SENTENCE" to false,
      ),
    )

    verify(deactivationService).deactivateOffender(
      same(offender),
      eq(reason),
      same(details),
      eq(false),
      eq(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_INELIGIBLE),
      eq("ESPRC"),
    )
  }

  @Test
  fun `eligibility failure for one offender does not stop later pages`() {
    val failing = offender("X000002", id = 2)
    val ineligible = offender("X000003", id = 3)
    val failingDetails = contactDetails(failing.crn)
    val ineligibleDetails = contactDetails(ineligible.crn)
    whenever(
      offenderRepository.findAllByStatusAndIdGreaterThanOrderByIdAsc(
        eq(OffenderStatus.VERIFIED),
        any(),
        any(),
      ),
    ).thenReturn(listOf(failing), listOf(ineligible), emptyList())
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(failing.crn))).thenReturn(listOf(failingDetails))
    whenever(ndiliusApiClient.getContactDetailsForMultiple(listOf(ineligible.crn))).thenReturn(listOf(ineligibleDetails))
    whenever(eligibilityChecker.check(same(failing), any(), any())).thenThrow(IllegalStateException("source unavailable"))
    whenever(eligibilityChecker.check(same(ineligible), any(), any()))
      .thenReturn(EligibilityResult(EligibilityCheckOutcome.INELIGIBLE, "Ineligible reason", "RULE"))

    job.process()

    verify(offenderRepository, times(3)).findAllByStatusAndIdGreaterThanOrderByIdAsc(
      eq(OffenderStatus.VERIFIED),
      any(),
      any(),
    )
    verify(deactivationService).deactivateOffender(
      same(ineligible),
      eq("Ineligible reason"),
      same(ineligibleDetails),
      eq(false),
      eq(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_INELIGIBLE),
      anyOrNull(),
    )
  }

  @Test
  fun `missing NDelius details are skipped without stopping the page`() {
    val missingDetailsOffender = offender("X000004", id = 4)
    val ineligible = offender("X000005", id = 5)
    val details = contactDetails(ineligible.crn)
    givenPage(listOf(missingDetailsOffender, ineligible), listOf(details))
    whenever(eligibilityChecker.check(same(ineligible), any(), any()))
      .thenReturn(EligibilityResult(EligibilityCheckOutcome.INELIGIBLE, "Ineligible reason", "RULE"))

    job.process()

    verify(eligibilityChecker, times(1)).check(any(), any(), any())
    verify(deactivationService).deactivateOffender(
      same(ineligible),
      eq("Ineligible reason"),
      same(details),
      eq(false),
      eq(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_INELIGIBLE),
      anyOrNull(),
    )
  }

  private fun givenPage(offenders: List<Offender>, details: List<ContactDetails>) {
    whenever(
      offenderRepository.findAllByStatusAndIdGreaterThanOrderByIdAsc(
        eq(OffenderStatus.VERIFIED),
        any(),
        any(),
      ),
    ).thenReturn(offenders, emptyList())
    whenever(ndiliusApiClient.getContactDetailsForMultiple(any())).thenReturn(details)
    whenever(eligibilityChecker.check(any(), any(), any()))
      .thenReturn(EligibilityResult(EligibilityCheckOutcome.ELIGIBLE, null, null))
  }

  private fun offender(crn: String, id: Long) = Offender(
    uuid = UUID.randomUUID(),
    crn = crn,
    practitionerId = "PRACT001",
    status = OffenderStatus.VERIFIED,
    firstCheckin = LocalDate.now(clock),
    checkinInterval = CheckinInterval.WEEKLY.duration,
    createdAt = clock.instant(),
    createdBy = "SYSTEM",
    updatedAt = clock.instant(),
    contactPreference = ContactPreference.EMAIL,
  ).also { ReflectionTestUtils.setField(it, "id", id) }

  private fun contactDetails(crn: String) = ContactDetails(
    crn = crn,
    name = Name("John", "Doe"),
    events = emptyList(),
    contactSuspended = false,
    dateOfBirth = LocalDate.of(1980, 1, 1),
  )
}
