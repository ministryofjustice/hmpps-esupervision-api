package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.GeneratingStubDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.MutableTestClock
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.TestClockConfiguration
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationV2Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignmentRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.CustomQuestionsReminderJob
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@TestConfiguration
class MockNotificationServiceConfiguration {
  @Bean
  @Primary
  fun notificationService(): NotificationV2Service = mock()
}

@TestConfiguration
class MockNdiliusClientConfiguration {
  @Bean
  @Primary
  fun ndiliusApiClient(): INdiliusApiClient = mock()
}

@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = ["spring.main.allow-bean-definition-overriding=true"],
)
@Import(TestClockConfiguration::class, MockNotificationServiceConfiguration::class, MockNdiliusClientConfiguration::class)
class CustomQuestionReminderJobIT : IntegrationTestBase() {

  @Autowired lateinit var clock: Clock

  @Autowired lateinit var job: CustomQuestionsReminderJob

  @Autowired lateinit var ndiliusApiClient: INdiliusApiClient

  @Autowired lateinit var notificationService: NotificationV2Service

  @Autowired lateinit var offenderV2Repository: OffenderV2Repository

  @Autowired lateinit var offenderCheckinV2Repository: OffenderCheckinV2Repository

  @Autowired lateinit var questionListItemRepository: DebugQuestionsRepository

  @Autowired lateinit var questionListAssignmentRepository: QuestionListAssignmentRepository

  @Autowired lateinit var questionDefinitionRepository: QuestionDefinitionRepository

  val dataProvider = GeneratingStubDataProvider()

  @BeforeEach
  fun setup() {
    (clock as MutableTestClock).advanceTo(Instant.now())

    val offender1 = offenderTemplate.copy(crn = "A000001", firstCheckin = clock.today(), uuid = UUID.randomUUID()).toEntity()
    val offender2 = offenderTemplate.copy(crn = "A000002", firstCheckin = clock.today().plusDays(1), uuid = UUID.randomUUID()).toEntity()
    val offender3 = offenderTemplate.copy(crn = "A000003", firstCheckin = clock.today().plusDays(3), uuid = UUID.randomUUID()).toEntity()
    offenderV2Repository.saveAll(listOf(offender1, offender2, offender3))
  }

  @AfterEach
  fun tearDown() {
    reset(notificationService)

    questionListItemRepository.deleteAllNonSystem()
    questionListAssignmentRepository.deleteAll()
    questionListItemRepository.deleteCustomQuestions()
    offenderCheckinV2Repository.deleteAll()
    offenderV2Repository.deleteAll()
  }

  @Test
  fun `execute the job`() {
    whenever(ndiliusApiClient.getContactDetailsForMultiple(any())).thenReturn(
      listOf("A000002", "A000003").map { dataProvider.provideCase(it) },
    )

    job.process()
    verify(notificationService, times(2)).sendPractitionerCustomQuestionsReminder(any())

    (clock as MutableTestClock).advanceBy(Duration.ofDays(1))
    reset(notificationService)

    job.process()
    verify(notificationService, times(0)).sendPractitionerCustomQuestionsReminder(any())
  }
}
