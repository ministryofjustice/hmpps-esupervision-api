package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
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
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityDataUnavailableException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityEvaluationEngine
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityRuleOperator
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityRuleOutcome
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityRuleRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.OffenderEligibilityCheckRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.OffenderEligibilityRule
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

class OffenderEligibilitySyncJobTest {

  private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))
  private val jobLogRepository: JobLogRepository = mock {
    on { saveAndFlush(any<JobLog>()) } doAnswer { it.arguments[0] as JobLog }
  }
  private val offenderRepository: OffenderRepository = mock()
  private val ndiliusApiClient: INdiliusApiClient = mock()
  private val eligibilityEvaluationEngine: EligibilityEvaluationEngine = mock()
  private val eligibilityRuleRepository: EligibilityRuleRepository = mock()
  private val offenderEligibilityCheckRepository: OffenderEligibilityCheckRepository = mock()
  private val offenderDeactivationService: OffenderDeactivationService = mock()

  private val job = OffenderEligibilitySyncJob(
    clock,
    jobLogRepository,
    offenderRepository,
    ndiliusApiClient,
    eligibilityEvaluationEngine,
    eligibilityRuleRepository,
    offenderEligibilityCheckRepository,
    offenderDeactivationService,
    chunkSize = 100,
  )

  private val ruleSet = "OFFENDER_ELIGIBILITY"

  @Test
  fun `process records a job log with the expected type and end time`() {
    stubChunk(emptyList(), emptyMap())

    job.process()

    val captor = argumentCaptor<JobLog>()
    verify(jobLogRepository, times(2)).saveAndFlush(captor.capture())
    val logEntry = captor.firstValue
    assertThat(logEntry.jobType).isEqualTo(OffenderEligibilitySyncJob.JOB_TYPE)
    assertThat(logEntry.createdAt).isEqualTo(clock.instant())
    assertThat(logEntry.endedAt).isEqualTo(clock.instant())
  }

  @Test
  fun `eligible offender is left untouched and the check is upserted`() {
    val offender = offender("X000001")
    stubChunk(listOf(offender), mapOf(offender.crn to details(offender.crn)))
    stubEvaluation(offender.crn, EligibilityResult(EligibilityCheckOutcome.ELIGIBLE, message = null, triggeredRuleCode = null))

    job.process()

    verify(offenderDeactivationService, never()).deactivateOffender(any(), any(), any(), any(), any())
    verify(offenderEligibilityCheckRepository).upsert(
      offenderId = eq(offender.id),
      ruleSet = eq(ruleSet),
      outcome = eq(EligibilityCheckOutcome.ELIGIBLE.name),
      message = eq(null),
      triggeredRuleCode = eq(null),
      checkedAt = any(),
    )
  }

  @Test
  fun `ineligible offender is deactivated using the triggered rule's configured audit event type`() {
    val offender = offender("X000002")
    stubChunk(listOf(offender), mapOf(offender.crn to details(offender.crn)))
    whenever(eligibilityRuleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(ruleSet)).thenReturn(
      listOf(rule("IS_CONTACT_SUSPENDED", auditEventType = "OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED")),
    )
    stubEvaluation(offender.crn, EligibilityResult(EligibilityCheckOutcome.INELIGIBLE, message = "Contact suspended", triggeredRuleCode = "IS_CONTACT_SUSPENDED"))

    job.process()

    verify(offenderDeactivationService).deactivateOffender(
      eq(offender),
      any(),
      any(),
      any(),
      eq(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED),
    )
    verify(offenderEligibilityCheckRepository).upsert(
      offenderId = eq(offender.id),
      ruleSet = eq(ruleSet),
      outcome = eq(EligibilityCheckOutcome.INELIGIBLE.name),
      message = eq("Contact suspended"),
      triggeredRuleCode = eq("IS_CONTACT_SUSPENDED"),
      checkedAt = any(),
    )
  }

  @Test
  fun `ineligible offender falls back to the generic audit event type when the rule has none configured`() {
    val offender = offender("X000003")
    stubChunk(listOf(offender), mapOf(offender.crn to details(offender.crn)))
    whenever(eligibilityRuleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(ruleSet)).thenReturn(
      listOf(rule("SOME_NEW_RULE", auditEventType = null)),
    )
    stubEvaluation(offender.crn, EligibilityResult(EligibilityCheckOutcome.INELIGIBLE, message = "Nope", triggeredRuleCode = "SOME_NEW_RULE"))

    job.process()

    verify(offenderDeactivationService).deactivateOffender(
      eq(offender),
      any(),
      any(),
      any(),
      eq(OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_INELIGIBLE),
    )
  }

  @Test
  fun `data unavailable during evaluation is recorded but does not deactivate`() {
    val offender = offender("X000004")
    stubChunk(listOf(offender), mapOf(offender.crn to details(offender.crn)))
    whenever(eligibilityEvaluationEngine.evaluate(eq(offender.crn), eq(ruleSet), any())).thenReturn(
      CompletableFuture.failedFuture(EligibilityDataUnavailableException("HAS_ACTIVE_EVENT", "NDELIUS", RuntimeException("timeout"))),
    )

    job.process()

    verify(offenderDeactivationService, never()).deactivateOffender(any(), any(), any(), any(), any())
    verify(offenderEligibilityCheckRepository).upsert(
      offenderId = eq(offender.id),
      ruleSet = eq(ruleSet),
      outcome = eq(EligibilityCheckOutcome.DATA_UNAVAILABLE.name),
      message = any(),
      triggeredRuleCode = eq(null),
      checkedAt = any(),
    )
  }

  @Test
  fun `missing NDelius contact details for a CRN is treated as data unavailable, not ineligible`() {
    val offender = offender("X000005")
    // contact details map is empty (NDelius returned nothing for this CRN)
    stubChunk(listOf(offender), emptyMap())
    whenever(eligibilityEvaluationEngine.evaluate(eq(offender.crn), eq(ruleSet), any())).thenReturn(
      CompletableFuture.failedFuture(EligibilityDataUnavailableException("HAS_ACTIVE_EVENT", "NDELIUS", RuntimeException("missing"))),
    )

    job.process()

    verify(offenderDeactivationService, never()).deactivateOffender(any(), any(), any(), any(), any())
    verify(offenderEligibilityCheckRepository).upsert(
      offenderId = eq(offender.id),
      ruleSet = eq(ruleSet),
      outcome = eq(EligibilityCheckOutcome.DATA_UNAVAILABLE.name),
      message = any(),
      triggeredRuleCode = eq(null),
      checkedAt = any(),
    )
  }

  private fun stubChunk(offenders: List<Offender>, detailsByCrn: Map<String, ContactDetails>) {
    for (offender in offenders) {
      whenever(offenderRepository.findById(eq(offender.id))).thenReturn(Optional.of(offender))
    }

    data class EligibilitySyncInfo(override val id: Long, override val crn: CRN) : OffenderRepository.IOffenderEligibilitySyncInfo
    whenever(offenderRepository.findVerifiedForEligibilitySync(any(), anyOrNull()))
      .thenReturn(offenders.map { EligibilitySyncInfo(it.id, it.crn) })
    whenever(ndiliusApiClient.getContactDetailsForMultiple(any())).thenReturn(detailsByCrn.values.toList())
    whenever(eligibilityEvaluationEngine.activeRuleSet).thenReturn(ruleSet)
    whenever(eligibilityRuleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(ruleSet)).thenReturn(emptyList())
    whenever(offenderDeactivationService.deactivateOffender(any(), any(), any(), any(), any())).thenAnswer { it.getArgument<Offender>(0) }
  }

  private fun stubEvaluation(crn: String, result: EligibilityResult) {
    whenever(eligibilityEvaluationEngine.evaluate(eq(crn), eq(ruleSet), any())).thenReturn(CompletableFuture.completedFuture(result))
  }

  private fun details(crn: String, events: List<Event> = emptyList(), suspended: Boolean = false) = ContactDetails(crn = crn, name = Name("John", "Doe"), events = events, contactSuspended = suspended, dateOfBirth = LocalDate.of(1980, 1, 1))

  private fun rule(code: String, auditEventType: String?) = OffenderEligibilityRule(
    ruleSet = ruleSet,
    ruleOrder = 1.0,
    code = code,
    question = "Is the rule met?",
    source = "NDELIUS",
    dataPoint = "SOME_FIELD",
    operator = EligibilityRuleOperator.IS_NOT_NULL,
    outcomeOnMatch = EligibilityRuleOutcome.NOT_ELIGIBLE,
    outcomeOnNoMatch = EligibilityRuleOutcome.CONTINUE,
    auditEventType = auditEventType,
    createdAt = clock.instant(),
    updatedAt = clock.instant(),
  )

  private var nextId = 1L

  private fun offender(crn: String) = Offender(
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
  ).also { org.springframework.test.util.ReflectionTestUtils.setField(it, "id", nextId++) }
}
