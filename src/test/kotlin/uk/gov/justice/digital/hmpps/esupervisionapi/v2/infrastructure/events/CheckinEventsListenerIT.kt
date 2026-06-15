package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinCreatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinReviewedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinSubmittedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.SurveyVersion
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

class CheckinEventsListenerIT : IntegrationTestBase() {

  @Autowired
  private lateinit var checkinEventsListener: CheckinEventsListener

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinV2Repository: OffenderCheckinRepository

  @Autowired
  private lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired
  private lateinit var genericNotificationRepository: GenericNotificationRepository

  @Autowired
  private lateinit var clock: Clock

  @AfterEach
  fun cleanUp() {
    genericNotificationRepository.deleteAll()
    genericNotificationRepository.flush()
    outboxItemRepository.deleteAll()
    checkinV2Repository.deleteAll()
    offenderRepository.deleteAll()
    offenderRepository.flush()
  }

  @Test
  fun `processEvent - checkin creation - mark outbox item as sent - success`() {
    val offender = offenderTemplate.copy(firstCheckin = LocalDate.now()).toEntity()
    offenderRepository.save(offender)

    val checkin = checkinV2Repository.save(createCheckin(offender))

    val event = CheckinCreatedEvent(
      checkinId = checkin.id,
      offenderId = offender.id,
      practitionerId = offender.practitionerId,
      checkin = checkin.dto(null, clock = clock),
      offenderContactPreference = ContactPreference.PHONE,
      currentEvent = 1,
    )

    checkinEventsListener.processEvent(event).get(2, TimeUnit.SECONDS)
    val outboxItem = outboxItemRepository.findByTypeAndEntityId(OutboxItemType.CHECKIN_CREATED, checkin.id).orElseThrow()
    assertEquals(OutboxItemStatus.SENT, outboxItem.status)
  }

  @Test
  fun `processEvent - checkin submission - mark outbox item as sent - success`() {
    val offender = offenderTemplate.copy(firstCheckin = LocalDate.now()).toEntity()
    offenderRepository.save(offender)

    val checkin = checkinV2Repository.save(createCheckin(offender))
    checkin.surveyResponse = mapOf("version" to SurveyVersion.V20260416Questions)
    checkin.status = CheckinStatus.SUBMITTED
    checkin.submittedAt = Instant.now()
    checkinV2Repository.save(checkin) // this will trigger the creation of outbox item

    val event = CheckinSubmittedEvent(
      checkinId = checkin.id,
      offenderId = offender.id,
      practitionerId = offender.practitionerId,
      checkin = checkin.dto(null, clock = clock),
      offenderContactPreference = ContactPreference.PHONE,
    )

    checkinEventsListener.processEvent(event).get(2, TimeUnit.SECONDS)

    val outboxItem = outboxItemRepository.findByTypeAndEntityId(OutboxItemType.CHECKIN_SUBMITTED, checkin.id).orElseThrow()
    assertEquals(OutboxItemStatus.SENT, outboxItem.status)
  }

  @Test
  fun `processEvent - checkin review - mark outbox item as sent - success`() {
    val offender = offenderTemplate.copy(firstCheckin = LocalDate.now()).toEntity()
    offenderRepository.save(offender)

    val checkin = checkinV2Repository.save(createCheckin(offender))
    checkin.surveyResponse = mapOf("version" to SurveyVersion.V20260416Questions)
    checkin.status = CheckinStatus.REVIEWED
    checkin.reviewedAt = Instant.now()
    checkin.reviewedBy = offender.practitionerId
    checkinV2Repository.save(checkin) // this will trigger the creation of outbox item

    val event = CheckinReviewedEvent(
      checkinId = checkin.id,
      offenderId = offender.id,
      practitionerId = offender.practitionerId,
      checkin = checkin.dto(null, clock = clock),
      offenderContactPreference = ContactPreference.PHONE,
    )

    checkinEventsListener.processEvent(event).get(2, TimeUnit.SECONDS)

    val outboxItem = outboxItemRepository.findByTypeAndEntityId(OutboxItemType.CHECKIN_REVIEWED, checkin.id).orElseThrow()
    assertEquals(OutboxItemStatus.SENT, outboxItem.status)
  }

  private fun createCheckin(offender: Offender): OffenderCheckin = OffenderCheckin(
    uuid = UUID.randomUUID(),
    offender = offender,
    status = CheckinStatus.CREATED,
    dueDate = LocalDate.now(),
    createdAt = Instant.now(),
    createdBy = offender.practitionerId,
  )

  @TestConfiguration
  class TestConfig {
    @Bean
    @Primary
    fun notificationV2Service(): NotificationService = mock()
  }
}
