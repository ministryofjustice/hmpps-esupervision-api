package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.util.ReflectionTestUtils
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.Feature
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinMode
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * ESUP-2223: the backfill schedules the next check-in for offenders set up before the feature was
 * turned on. The feature flag is switched on per test, so this shares the default test context.
 */
class UpcomingCheckinBackfillJobIT : IntegrationTestBase() {

  @Autowired
  private lateinit var job: UpcomingCheckinBackfillJob

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  private lateinit var deliusEventRepository: CheckinDeliusEventRepository

  @Autowired
  private lateinit var jobLogRepository: JobLogRepository

  @Autowired
  private lateinit var appConfig: AppConfig

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  private lateinit var clock: Clock

  private lateinit var featuresBefore: Set<Feature>

  private val today get() = clock.today()

  private var crnSequence = 0

  @BeforeEach
  fun enableFeature() {
    featuresBefore = appConfig.enabledFeatures
    setFeatures(featuresBefore + Feature.ESUP_2223)
  }

  @AfterEach
  fun tearDown() {
    setFeatures(featuresBefore)
    jdbcTemplate.update("TRUNCATE TABLE checkin_delius_event_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE job_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_checkin_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_v2 RESTART IDENTITY CASCADE")
  }

  @Test
  fun `schedules the next check-in for offenders with neither an upcoming nor an open check-in`() {
    val notScheduled = saveOffender(firstCheckin = today.plusDays(3))
    val open = saveOffender(firstCheckin = today.minusDays(1))
    saveCheckin(open, CheckinStatus.CREATED, today.minusDays(1))
    val alreadyScheduled = saveOffender(firstCheckin = today.plusDays(2))
    saveCheckin(alreadyScheduled, CheckinStatus.SCHEDULED, today.plusDays(2))
    val notVerified = saveOffender(firstCheckin = today.plusDays(3), status = OffenderStatus.INACTIVE)

    job.process()

    val scheduled = scheduledCheckins(notScheduled).single()
    assertThat(scheduled.dueDate).isEqualTo(today.plusDays(3))
    assertThat(scheduledCheckins(open)).isEmpty()
    assertThat(scheduledCheckins(notVerified)).isEmpty()
    assertThat(deliusEventRepository.findAll()).singleElement().satisfies({
      assertThat(it.eventType).isEqualTo(DomainEventType.V2_CHECKIN_SCHEDULED)
      assertThat(it.checkinUuid).isEqualTo(scheduled.uuid)
    })
    assertThat(jobLogRepository.findAll().single { it.jobType == UpcomingCheckinBackfillJob.JOB_TYPE }.endedAt).isNotNull()
  }

  @Test
  fun `running it again schedules nothing more`() {
    saveOffender(firstCheckin = today.plusDays(3))
    job.process()

    job.process()

    assertThat(deliusEventRepository.findAll()).hasSize(1)
  }

  @Test
  fun `covers more offenders than fit in one chunk`() {
    repeat(105) { saveOffender(firstCheckin = today.plusDays(3)) }

    job.process()

    assertThat(checkinRepository.findAll().filter { it.status == CheckinStatus.SCHEDULED }).hasSize(105)
  }

  @Test
  fun `does not run while today's creation job is running`() {
    val offender = saveOffender(firstCheckin = today.plusDays(3))
    jobLogRepository.save(JobLog(jobType = CheckinCreationJob.JOB_TYPE, createdAt = clock.instant()))

    job.process()

    assertThat(scheduledCheckins(offender)).isEmpty()
  }

  @Test
  fun `runs once today's creation job has finished`() {
    val offender = saveOffender(firstCheckin = today.plusDays(3))
    jobLogRepository.save(JobLog(jobType = CheckinCreationJob.JOB_TYPE, createdAt = clock.instant()).apply { endedAt = clock.instant() })

    job.process()

    assertThat(scheduledCheckins(offender)).hasSize(1)
  }

  @Test
  fun `does nothing with the feature off`() {
    setFeatures(featuresBefore - Feature.ESUP_2223)
    val offender = saveOffender(firstCheckin = today.plusDays(3))

    job.process()

    assertThat(scheduledCheckins(offender)).isEmpty()
  }

  private fun setFeatures(features: Set<Feature>) = ReflectionTestUtils.setField(appConfig, "enabledFeatures", features)

  private fun scheduledCheckins(offender: Offender) = checkinRepository.findAllByOffenderAndStatus(offender, CheckinStatus.SCHEDULED)

  private fun saveOffender(firstCheckin: LocalDate, status: OffenderStatus = OffenderStatus.VERIFIED): Offender = offenderRepository.save(
    offenderTemplate.copy(
      uuid = UUID.randomUUID(),
      crn = "B${100000 + crnSequence++}",
      status = status,
      firstCheckin = firstCheckin,
      mode = CheckinMode.SCHEDULED,
      checkinInterval = CheckinInterval.WEEKLY,
    ).toEntity(),
  )

  private fun saveCheckin(offender: Offender, status: CheckinStatus, dueDate: LocalDate): OffenderCheckin = checkinRepository.save(
    OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = offender,
      status = status,
      dueDate = dueDate,
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
    ),
  )
}
